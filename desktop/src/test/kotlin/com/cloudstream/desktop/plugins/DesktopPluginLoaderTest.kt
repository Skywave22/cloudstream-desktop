package com.cloudstream.desktop.plugins

import com.lagradost.cloudstream3.plugins.BasePlugin
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPluginLoaderTest {
    @AfterTest
    fun cleanUpLoader() {
        DesktopPluginLoader.unloadAll()
    }

    @Test
    fun `DEX-only cs3 archive reports a useful compatibility error`() {
        val directory = Files.createTempDirectory("cloudstream-plugin-test")
        try {
            val plugin = directory.resolve("android-plugin.CS3")
            JarOutputStream(plugin.outputStream()).use { archive ->
                archive.putNextEntry(JarEntry("classes.dex"))
                archive.write(byteArrayOf(0x64, 0x65, 0x78))
                archive.closeEntry()
            }

            val result = DesktopPluginLoader.loadPlugins(directory.toFile()).single()
            assertFalse(result.successful)
            assertTrue(result.error.orEmpty().contains("DEX-only"))
            assertTrue(result.error.orEmpty().contains("desktop .jar"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `JVM jar loads the manifest entry class and unloads it cleanly`() {
        val directory = Files.createTempDirectory("cloudstream-plugin-test")
        TestJvmPlugin.loaded = false
        TestJvmPlugin.unloaded = false
        try {
            val plugin = directory.resolve("desktop-plugin.jar")
            val className = TestJvmPlugin::class.java.name
            val classResource = className.replace('.', '/') + ".class"
            JarOutputStream(plugin.outputStream()).use { archive ->
                archive.putNextEntry(JarEntry(classResource))
                checkNotNull(TestJvmPlugin::class.java.classLoader.getResourceAsStream(classResource)).use {
                    it.copyTo(archive)
                }
                archive.closeEntry()
                archive.putNextEntry(JarEntry("manifest.json"))
                archive.write("{\"name\":\"Test\",\"pluginClassName\":\"$className\",\"version\":1}".toByteArray())
                archive.closeEntry()
            }

            val result = DesktopPluginLoader.loadPlugins(directory.toFile()).single()
            assertTrue(result.successful, result.error)
            assertTrue(TestJvmPlugin.loaded)

            DesktopPluginLoader.unloadAll()
            assertTrue(TestJvmPlugin.unloaded)
        } finally {
            DesktopPluginLoader.unloadAll()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `non-directory plugin path is rejected`() {
        val file = Files.createTempFile("cloudstream-plugin-test", ".tmp").toFile()
        try {
            val result = DesktopPluginLoader.loadPlugins(file).single()
            assertFalse(result.successful)
            assertTrue(result.error.orEmpty().contains("not a directory"))
        } finally {
            file.delete()
        }
    }

    class TestJvmPlugin : BasePlugin() {
        override fun load() {
            loaded = true
        }

        override fun beforeUnload() {
            unloaded = true
        }

        companion object {
            var loaded: Boolean = false
            var unloaded: Boolean = false
        }
    }
}
