package com.cloudstream.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.cloudstream.desktop.plugins.DesktopPluginLoader
import com.cloudstream.desktop.ui.App
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.network.WebViewResolver
import javafx.application.Platform
import java.io.File

private class DesktopTmdbProvider : TmdbProvider() {
    // The base TmdbProvider expects a site-specific subclass by default. Desktop uses it
    // directly, so provide its identity and return metadata instead of null from load().
    override var name: String = "TMDB"
    override var mainUrl: String = "https://www.themoviedb.org"
    override val useMetaLoadResponse: Boolean = true
}

fun main() {
    WebViewResolver.ensureJavaFx()
    Platform.setImplicitExit(false)

    val tmdb = DesktopTmdbProvider()
    APIHolder.allProviders.add(tmdb)
    APIHolder.addPluginMapping(tmdb)

    val pluginsDir = File(System.getProperty("user.home"), ".cloudstream/plugins")
    DesktopPluginLoader.loadPlugins(pluginsDir)
    APIHolder.initAll()

    try {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "CloudStream Desktop",
            ) {
                App()
            }
        }
    } finally {
        DesktopPluginLoader.unloadAll()
        Platform.exit()
    }
}
