package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.mvvm.debugException
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.nicehttp.requestCreator
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.web.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * JVM implementation of WebViewResolver using JavaFX WebView.
 * Note: Works for many scraping flows, but may not bypass heavy Cloudflare checks
 * as well as a full Chromium engine (JCEF) would.
 */
actual class WebViewResolver actual constructor(
    interceptUrl: Regex,
    additionalUrls: List<Regex>,
    userAgent: String?,
    useOkhttp: Boolean,
    script: String?,
    scriptCallback: ((String) -> Unit)?,
    timeout: Long
) : Interceptor {

    private val interceptUrl = interceptUrl
    private val additionalUrls = additionalUrls
    private val userAgent = userAgent
    private val script = script
    private val scriptCallback = scriptCallback
    private val timeout = timeout

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(request)
    }

    actual companion object {
        actual val DEFAULT_TIMEOUT = 60_000L
        actual var webViewUserAgent: String? = null

        private val javaFxStarted = AtomicBoolean(false)

        fun ensureJavaFx() {
            if (javaFxStarted.compareAndSet(false, true)) {
                try {
                    Platform.startup { }
                } catch (e: IllegalStateException) {
                    // JavaFX already started implicitly
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
        requestCallBack: (Request) -> Boolean
    ): Pair<Request?, List<Request>> {
        return try {
            resolveUsingWebView(
                requestCreator(method, url, referer = referer, headers = headers), requestCallBack
            )
        } catch (e: java.lang.IllegalArgumentException) {
            logError(e)
            debugException { "ILLEGAL URL IN resolveUsingWebView!" }
            return null to emptyList()
        }
    }

    actual suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean
    ): Pair<Request?, List<Request>> {
        ensureJavaFx()

        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { continuation ->
                try {
                    Platform.runLater {
                        val webView = WebView().apply {
                            prefWidth = 1280.0
                            prefHeight = 720.0
                        }
                        val engine = webView.engine
                        val matched = mutableListOf<Request>()
                        var intercepted: Request? = null

                        val locListener = javafx.beans.value.ChangeListener<String> { _, _, newLoc ->
                            if (newLoc != null && newLoc.isNotBlank() && newLoc != "about:blank") {
                                try {
                                    val req = Request.Builder().url(newLoc).build()
                                    if (requestCallBack(req)) {
                                        if (intercepted == null && interceptUrl.matches(newLoc)) {
                                            intercepted = req
                                        }
                                        if (additionalUrls.any { it.matches(newLoc) }) {
                                            matched.add(req)
                                        }
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                        engine.locationProperty().addListener(locListener)

                        val stateListener = object : javafx.beans.value.ChangeListener<Worker.State> {
                            override fun changed(
                                observable: javafx.beans.value.ObservableValue<out Worker.State>?,
                                oldValue: Worker.State?,
                                newValue: Worker.State?
                            ) {
                                if (newValue == Worker.State.SUCCEEDED || newValue == Worker.State.FAILED) {
                                    val loc = engine.location ?: ""
                                    if (loc.isNotBlank() && loc != "about:blank") {
                                        try {
                                            val req = Request.Builder().url(loc).build()
                                            if (requestCallBack(req)) {
                                                if (intercepted == null && interceptUrl.matches(loc)) {
                                                    intercepted = req
                                                }
                                                if (additionalUrls.any { it.matches(loc) }) {
                                                    matched.add(req)
                                                }
                                            }
                                        } catch (_: Exception) { }
                                    }

                                    if (script != null && newValue == Worker.State.SUCCEEDED) {
                                        try {
                                            val result = engine.executeScript(script)
                                            scriptCallback?.invoke(result?.toString() ?: "")
                                        } catch (t: Throwable) {
                                            t.printStackTrace()
                                        }
                                    }

                                    engine.loadWorker.stateProperty().removeListener(this)
                                    engine.locationProperty().removeListener(locListener)
                                    continuation.resume(intercepted to matched)
                                }
                            }
                        }
                        engine.loadWorker.stateProperty().addListener(stateListener)

                        userAgent?.let { ua ->
                            try {
                                val pageField = webView.javaClass.getDeclaredField("page")
                                pageField.isAccessible = true
                                val page = pageField.get(webView)
                                val setUa = page.javaClass.getDeclaredMethod("setUserAgentString", String::class.java)
                                setUa.invoke(page, ua)
                            } catch (_: Exception) { }
                        }

                        engine.load(request.url.toString())
                    }
                } catch (t: Throwable) {
                    continuation.resume(null to emptyList())
                }
            }
        } ?: (null to emptyList())
    }
}
