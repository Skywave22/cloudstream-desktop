package com.cloudstream.desktop.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import java.awt.BorderLayout
import javax.swing.JPanel

@Composable
fun PlayerScreen(url: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) { Text("Back") }
            Text("Player", style = MaterialTheme.typography.h6)
        }

        SwingPanel(
            factory = {
                JPanel().apply {
                    layout = BorderLayout()
                    val jfxPanel = JFXPanel()
                    add(jfxPanel, BorderLayout.CENTER)

                    Platform.runLater {
                        val webView = WebView()
                        val scene = Scene(webView)
                        jfxPanel.scene = scene

                        val html = buildPlayerHtml(url)
                        webView.engine.loadContent(html)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun buildPlayerHtml(url: String): String {
    val type = when {
        url.endsWith(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
        url.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        else -> "video/mp4"
    }
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <style>
                body, html { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; background: #000; }
                video { width: 100%; height: 100%; }
            </style>
        </head>
        <body>
            <video id="player" controls autoplay>
                <source src="$url" type="$type">
                Your browser does not support the video tag.
            </video>
            <script>
                const video = document.getElementById('player');
                video.play().catch(() => {});
            </script>
        </body>
        </html>
    """.trimIndent()
}
