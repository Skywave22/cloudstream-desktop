package com.cloudstream.desktop.plugins

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import java.io.File
import java.util.jar.JarFile
import java.net.URLClassLoader

object DesktopPluginLoader {
    fun loadPlugins(directory: File) {
        if (!directory.exists()) {
            directory.mkdirs()
            println("Created plugins directory: ${directory.absolutePath}")
            return
        }

        directory.listFiles { f -> f.extension == "jar" || f.extension == "cs3" }?.forEach { file ->
            try {
                loadPlugin(file)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadPlugin(file: File) {
        val classLoader = URLClassLoader(
            arrayOf(file.toURI().toURL()),
            this::class.java.classLoader
        )

        JarFile(file).use { jar ->
            jar.entries().asSequence().forEach { entry ->
                if (entry.name.endsWith(".class")) {
                    val className = entry.name.replace('/', '.').removeSuffix(".class")
                    try {
                        val clazz = classLoader.loadClass(className)
                        val annotation = clazz.getAnnotation(CloudstreamPlugin::class.java)
                        if (annotation != null && BasePlugin::class.java.isAssignableFrom(clazz)) {
                            val plugin = clazz.getDeclaredConstructor().newInstance() as BasePlugin
                            plugin.filename = file.absolutePath
                            plugin.load()
                            println("Loaded plugin: ${clazz.simpleName} from ${file.name}")
                        }
                    } catch (e: NoClassDefFoundError) {
                        // Android-specific classes won't load on JVM; skip silently
                    } catch (e: ClassNotFoundException) {
                        // Dependencies missing; skip
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}
