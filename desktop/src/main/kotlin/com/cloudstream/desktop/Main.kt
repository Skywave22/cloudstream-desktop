package com.cloudstream.desktop

import com.cloudstream.desktop.plugins.DesktopPluginLoader
import com.cloudstream.desktop.ui.App
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import javafx.application.Platform
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File

fun main() {
    try {
        Platform.startup { }
    } catch (e: IllegalStateException) {
        // Already started
    }

    // Register built-in meta providers available on JVM
    val tmdb = TmdbProvider()
    APIHolder.allProviders.add(tmdb)
    APIHolder.addPluginMapping(tmdb)

    // Load any desktop-compatible plugins from ~/.cloudstream/plugins
    val pluginsDir = File(System.getProperty("user.home"), ".cloudstream/plugins")
    DesktopPluginLoader.loadPlugins(pluginsDir)

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "CloudStream Desktop",
        ) {
            App()
        }
    }
}
