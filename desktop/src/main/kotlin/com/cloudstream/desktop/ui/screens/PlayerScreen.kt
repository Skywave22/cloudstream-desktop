package com.cloudstream.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import com.cloudstream.desktop.playback.PlaybackSource
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JPanel

@Composable
fun PlayerScreen(source: PlaybackSource, onBack: () -> Unit) {
    val panelReference = remember { AtomicReference<JavaFxPlayerPanel?>() }

    DisposableEffect(Unit) {
        onDispose {
            panelReference.getAndSet(null)?.disposePlayer()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBack) { Text("Back") }
            Column(horizontalAlignment = Alignment.End) {
                Text(source.name.ifBlank { "Player" }, style = MaterialTheme.typography.h6)
                Text(source.displayName, style = MaterialTheme.typography.caption)
            }
        }

        if (source.requiresCustomHeaders) {
            Text(
                "This host requires custom HTTP headers. JavaFX WebView cannot guarantee those headers for media requests.",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colors.secondary,
                style = MaterialTheme.typography.caption,
            )
        }

        SwingPanel(
            factory = {
                JavaFxPlayerPanel().also {
                    panelReference.set(it)
                    it.load(source)
                }
            },
            update = { it.load(source) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private class JavaFxPlayerPanel : JPanel(BorderLayout()) {
    private val jfxPanel = JFXPanel()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var pendingHtml: String? = null

    @Volatile
    private var loadedSource: PlaybackSource? = null

    init {
        add(jfxPanel, BorderLayout.CENTER)
        runOnJavaFxThread {
            val view = WebView()
            webView = view
            jfxPanel.scene = Scene(view)
            pendingHtml?.let(view.engine::loadContent)
        }
    }

    fun load(source: PlaybackSource) {
        if (loadedSource == source) return
        loadedSource = source
        val html = buildPlayerHtml(source)
        pendingHtml = html
        runOnJavaFxThread {
            webView?.engine?.loadContent(html)
        }
    }

    fun disposePlayer() {
        loadedSource = null
        pendingHtml = null
        runOnJavaFxThread {
            webView?.engine?.loadContent("<html><body style='background:#000'></body></html>")
            webView = null
            jfxPanel.scene = null
        }
    }
}

private fun runOnJavaFxThread(block: () -> Unit) {
    if (Platform.isFxApplicationThread()) {
        block()
    } else {
        try {
            Platform.runLater(block)
        } catch (_: IllegalStateException) {
            // The JavaFX toolkit is already shutting down.
        }
    }
}

internal fun buildPlayerHtml(source: PlaybackSource): String {
    val unsupportedReason = when (source.type) {
        ExtractorLinkType.TORRENT, ExtractorLinkType.MAGNET ->
            "Torrent and magnet playback is not supported by the embedded player."
        ExtractorLinkType.DASH ->
            "DASH playback is not supported by JavaFX WebView."
        else -> null
    }

    val sourceUrl = source.url.toJavaScriptString()
    val mimeType = source.type.getMimeType().toJavaScriptString()
    val unsupported = unsupportedReason?.toJavaScriptString() ?: "null"

    return """
        <!doctype html>
        <html lang="en">
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                * { box-sizing: border-box; }
                html, body { width: 100%; height: 100%; margin: 0; background: #000; color: #fff; font-family: sans-serif; }
                body { display: flex; align-items: center; justify-content: center; }
                video { width: 100%; height: 100%; background: #000; }
                #status { position: absolute; max-width: 80%; padding: 12px 16px; border-radius: 6px; background: rgba(20,20,20,.9); text-align: center; }
                .hidden { display: none; }
            </style>
        </head>
        <body>
            <video id="player" controls autoplay playsinline></video>
            <div id="status" class="hidden" role="alert"></div>
            <script>
                (() => {
                    const url = $sourceUrl;
                    const mime = $mimeType;
                    const unsupported = $unsupported;
                    const player = document.getElementById('player');
                    const status = document.getElementById('status');
                    const showError = message => {
                        status.textContent = message;
                        status.classList.remove('hidden');
                    };

                    if (unsupported) {
                        player.classList.add('hidden');
                        showError(unsupported);
                        return;
                    }

                    const mediaSource = document.createElement('source');
                    mediaSource.src = url;
                    mediaSource.type = mime;
                    player.appendChild(mediaSource);
                    player.addEventListener('playing', () => status.classList.add('hidden'));
                    player.addEventListener('error', () => {
                        showError('Playback failed. The codec, stream format, CORS policy, or required request headers may not be supported by JavaFX.');
                    });
                    player.load();
                    player.play().catch(error => showError('Autoplay was blocked: ' + error.message));
                })();
            </script>
        </body>
        </html>
    """.trimIndent()
}

private fun String.toJavaScriptString(): String = buildString(length + 2) {
    append('"')
    for (character in this@toJavaScriptString) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '<' -> append("\\u003C")
            '>' -> append("\\u003E")
            '&' -> append("\\u0026")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> if (character.code < 0x20) {
                append("\\u%04X".format(character.code))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
