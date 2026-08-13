package com.lagradost.cloudstream3.network

import com.lagradost.api.Log
import com.lagradost.cloudstream3.mvvm.debugException
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.nicehttp.requestCreator
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.concurrent.Worker
import javafx.event.EventHandler
import javafx.scene.web.WebView
import javafx.util.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

private const val TAG = "WebViewResolver"
private const val RESOURCE_POLL_INTERVAL_MS = 200.0

/**
 * JVM WebView resolver backed by JavaFX WebView.
 *
 * JavaFX does not expose WebKit's request interception API. This implementation observes top-level
 * navigation and the browser Resource Timing API, which covers redirects and many fetch/media
 * requests without relying on inaccessible JavaFX internals.
 */
actual class WebViewResolver actual constructor(
    private val interceptUrl: Regex,
    private val additionalUrls: List<Regex>,
    private val userAgent: String?,
    @Suppress("unused") private val useOkhttp: Boolean,
    private val script: String?,
    private val scriptCallback: ((String) -> Unit)?,
    private val timeout: Long,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (Platform.isFxApplicationThread()) {
            // Blocking the FX thread would prevent WebView from making progress and deadlock.
            Log.w(TAG, "Skipping WebView interception on the JavaFX application thread")
            return chain.proceed(request)
        }

        val resolvedRequest = runBlocking {
            resolveUsingWebView(request).first
        }
        return chain.proceed(resolvedRequest ?: request)
    }

    actual companion object {
        actual val DEFAULT_TIMEOUT = 60_000L
        actual var webViewUserAgent: String? = null

        private val javaFxStarted = AtomicBoolean(false)

        fun ensureJavaFx() {
            if (javaFxStarted.compareAndSet(false, true)) {
                try {
                    Platform.startup { }
                } catch (_: IllegalStateException) {
                    // JavaFX was started implicitly (for example, by JFXPanel).
                } catch (error: Throwable) {
                    javaFxStarted.set(false)
                    throw error
                }
            }
        }
    }

    actual suspend fun resolveUsingWebView(
        url: String,
        referer: String?,
        method: String,
        requestCallBack: (Request) -> Boolean,
    ): Pair<Request?, List<Request>> =
        resolveUsingWebView(url, referer, emptyMap(), method, requestCallBack)

    actual suspend fun resolveUsingWebView(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        method: String,
        requestCallBack: (Request) -> Boolean,
    ): Pair<Request?, List<Request>> {
        return try {
            resolveUsingWebView(
                requestCreator(method, url, referer = referer, headers = headers),
                requestCallBack,
            )
        } catch (error: IllegalArgumentException) {
            logError(error)
            debugException { "ILLEGAL URL IN resolveUsingWebView!" }
            null to emptyList()
        }
    }

    actual suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean,
    ): Pair<Request?, List<Request>> {
        ensureJavaFx()

        val intercepted = AtomicReference<Request?>(null)
        val matched = Collections.synchronizedList(mutableListOf<Request>())
        val cleanup = AtomicReference<(() -> Unit)?>(null)

        val result = withTimeoutOrNull(timeout.coerceAtLeast(1L)) {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)

                fun snapshot(): Pair<Request?, List<Request>> =
                    intercepted.get() to synchronized(matched) { matched.toList() }

                fun finish() {
                    if (!completed.compareAndSet(false, true)) return
                    try {
                        cleanup.getAndSet(null)?.invoke()
                    } catch (error: Exception) {
                        Log.w(TAG, "WebView cleanup failed: ${error.message}")
                    } finally {
                        if (continuation.isActive) continuation.resume(snapshot())
                    }
                }

                continuation.invokeOnCancellation {
                    if (completed.compareAndSet(false, true)) {
                        runOnJavaFxThread {
                            try {
                                cleanup.getAndSet(null)?.invoke()
                            } catch (error: Exception) {
                                Log.w(TAG, "WebView cleanup failed: ${error.message}")
                            }
                        }
                    }
                }

                runOnJavaFxThread {
                    if (!continuation.isActive) return@runOnJavaFxThread
                    try {
                        val webView = WebView().apply {
                            prefWidth = 1280.0
                            prefHeight = 720.0
                        }
                        val engine = webView.engine
                        engine.isJavaScriptEnabled = true
                        webViewUserAgent = engine.userAgent
                        userAgent?.let { engine.userAgent = it }

                        val seenUrls = mutableSetOf<String>()
                        var scriptExecutedForLocation: String? = null

                        fun requestFor(candidateUrl: String): Request? = try {
                            if (candidateUrl == request.url.toString()) {
                                request
                            } else {
                                Request.Builder()
                                    .url(candidateUrl)
                                    .headers(request.headers)
                                    .get()
                                    .build()
                            }
                        } catch (_: IllegalArgumentException) {
                            null
                        }

                        fun inspectUrl(candidateUrl: String?) {
                            if (completed.get()) return
                            val url = candidateUrl?.trim().orEmpty()
                            if (url.isEmpty() || url == "about:blank" || !seenUrls.add(url)) return

                            val candidate = requestFor(url) ?: return
                            if (interceptUrl.containsMatchIn(url)) {
                                try {
                                    requestCallBack(candidate)
                                } catch (error: Throwable) {
                                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                                    Log.w(TAG, "Request callback failed: ${error.message}")
                                }
                                intercepted.compareAndSet(null, candidate)
                                finish()
                                return
                            }

                            if (additionalUrls.any { it.containsMatchIn(url) }) {
                                matched += candidate
                                val shouldStop = try {
                                    requestCallBack(candidate)
                                } catch (error: Throwable) {
                                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                                    Log.w(TAG, "Request callback failed: ${error.message}")
                                    false
                                }
                                if (shouldStop) finish()
                            }
                        }

                        fun inspectResourceTiming() {
                            if (completed.get()) return
                            inspectUrl(engine.location)
                            val resources = try {
                                engine.executeScript(
                                    "performance.getEntriesByType('resource').map(function(entry) { return entry.name; }).join('\\n')",
                                ) as? String
                            } catch (error: Throwable) {
                                if (error is VirtualMachineError || error is ThreadDeath) throw error
                                null
                            }
                            resources?.lineSequence()?.forEach(::inspectUrl)
                        }

                        val locationListener = ChangeListener<String> { _, _, location ->
                            inspectUrl(location)
                        }
                        val stateListener = ChangeListener<Worker.State> { _, _, state ->
                            if (state == Worker.State.SUCCEEDED) {
                                inspectResourceTiming()
                                val location = engine.location
                                if (script != null && scriptExecutedForLocation != location) {
                                    scriptExecutedForLocation = location
                                    try {
                                        val value = engine.executeScript(script)
                                        scriptCallback?.invoke(value?.toString().orEmpty())
                                    } catch (error: Throwable) {
                                        if (error is VirtualMachineError || error is ThreadDeath) throw error
                                        Log.w(TAG, "Injected script failed: ${error.message}")
                                    }
                                }
                            }
                        }
                        engine.locationProperty().addListener(locationListener)
                        engine.loadWorker.stateProperty().addListener(stateListener)

                        val poller = Timeline(
                            KeyFrame(
                                Duration.millis(RESOURCE_POLL_INTERVAL_MS),
                                EventHandler { inspectResourceTiming() },
                            ),
                        ).apply {
                            cycleCount = Timeline.INDEFINITE
                            play()
                        }

                        cleanup.set {
                            poller.stop()
                            engine.locationProperty().removeListener(locationListener)
                            engine.loadWorker.stateProperty().removeListener(stateListener)
                            engine.loadWorker.cancel()
                            engine.loadContent("")
                        }

                        Log.i(TAG, "Initial WebView request: ${request.url}")
                        engine.load(request.url.toString())
                    } catch (error: Throwable) {
                        if (error is VirtualMachineError || error is ThreadDeath) throw error
                        Log.e(TAG, "WebView resolution failed: ${error.message ?: error::class.simpleName}")
                        finish()
                    }
                }
            }
        }

        return result ?: (intercepted.get() to synchronized(matched) { matched.toList() })
    }
}

private fun runOnJavaFxThread(block: () -> Unit) {
    if (Platform.isFxApplicationThread()) {
        block()
    } else {
        try {
            Platform.runLater(block)
        } catch (_: IllegalStateException) {
            // JavaFX is shutting down; there is nothing left to clean up.
        }
    }
}
