package com.cloudstream.desktop.plugins

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.extractorApis
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.jar.JarFile

private const val TAG = "DesktopPluginLoader"

/** Result for one plugin archive. Standard Android DEX-only .cs3 files are not JVM compatible. */
data class PluginLoadResult(
    val file: File,
    val pluginClasses: List<String> = emptyList(),
    val error: String? = null,
    val alreadyLoaded: Boolean = false,
) {
    val successful: Boolean
        get() = error == null
}

object DesktopPluginLoader {
    private data class LoadedPlugin(
        val classLoader: URLClassLoader,
        val instances: List<BasePlugin>,
    )

    private val loadedPlugins = linkedMapOf<String, LoadedPlugin>()
    private val objectMapper: ObjectMapper = mapper

    @Synchronized
    fun loadPlugins(directory: File): List<PluginLoadResult> {
        if (directory.exists() && !directory.isDirectory) {
            val result = PluginLoadResult(directory, error = "Plugin path exists but is not a directory")
            Log.e(TAG, "${directory.absolutePath}: ${result.error}")
            return listOf(result)
        }
        if (!directory.exists() && !directory.mkdirs()) {
            val result = PluginLoadResult(directory, error = "Could not create the plugin directory")
            Log.e(TAG, "${directory.absolutePath}: ${result.error}")
            return listOf(result)
        }

        val archives = directory.listFiles { file ->
            file.isFile && file.extension.lowercase() in setOf("jar", "cs3")
        }?.sortedBy { it.name.lowercase() }.orEmpty()

        if (archives.isEmpty()) {
            Log.i(TAG, "No JVM plugins found in ${directory.absolutePath}")
        }
        return archives.map(::loadPlugin)
    }

    @Synchronized
    fun unloadAll() {
        loadedPlugins.entries.toList().asReversed().forEach { (path, loaded) ->
            loaded.instances.asReversed().forEach { plugin ->
                try {
                    plugin.beforeUnload()
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                    Log.e(TAG, "Plugin unload failed for $path: ${error.message ?: error::class.simpleName}")
                }
            }

            removeContributions(path)

            try {
                loaded.classLoader.close()
            } catch (error: Exception) {
                Log.w(TAG, "Could not close plugin archive $path: ${error.message}")
            }
        }
        loadedPlugins.clear()
    }

    @Synchronized
    private fun loadPlugin(inputFile: File): PluginLoadResult {
        val file = try {
            inputFile.canonicalFile
        } catch (error: Exception) {
            return PluginLoadResult(inputFile, error = error.message ?: "Invalid plugin path")
        }
        val path = file.absolutePath
        loadedPlugins[path]?.let {
            return PluginLoadResult(
                file = file,
                pluginClasses = it.instances.mapNotNull { plugin -> plugin::class.qualifiedName },
                alreadyLoaded = true,
            )
        }

        var classLoader: URLClassLoader? = null
        val instances = mutableListOf<BasePlugin>()
        return try {
            val archiveInfo = inspectArchive(file)
            if (archiveInfo.classNames.isEmpty()) {
                val reason = if (archiveInfo.containsDex) {
                    "Android DEX-only .cs3 plugins cannot run on the JVM; install a desktop .jar build"
                } else {
                    "Archive contains no JVM .class files"
                }
                return PluginLoadResult(file, error = reason)
            }

            classLoader = URLClassLoader(arrayOf(file.toURI().toURL()), this::class.java.classLoader)
            val candidates = archiveInfo.manifestClassName?.let(::listOf)
                ?: archiveInfo.classNames.filterNot { '$' in it }

            for (className in candidates) {
                val plugin = instantiatePlugin(classLoader, className, archiveInfo.manifestClassName != null)
                    ?: continue
                plugin.filename = path
                try {
                    plugin.load()
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                    try {
                        plugin.beforeUnload()
                    } catch (_: Throwable) {
                        // The original load error is more useful.
                    }
                    throw PluginLoadException("$className failed during load: ${error.message ?: error::class.simpleName}", error)
                }
                instances += plugin
            }

            if (instances.isEmpty()) {
                classLoader.close()
                PluginLoadResult(
                    file,
                    error = archiveInfo.manifestClassName?.let { "Manifest plugin class '$it' is not a valid BasePlugin" }
                        ?: "No @CloudstreamPlugin BasePlugin class was found",
                )
            } else {
                loadedPlugins[path] = LoadedPlugin(classLoader, instances.toList())
                val classNames = instances.mapNotNull { it::class.qualifiedName }
                Log.i(TAG, "Loaded ${classNames.joinToString()} from ${file.name}")
                PluginLoadResult(file, pluginClasses = classNames)
            }
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            instances.asReversed().forEach { plugin ->
                try {
                    plugin.beforeUnload()
                } catch (_: Throwable) {
                    // Best-effort rollback.
                }
            }
            removeContributions(path)
            try {
                classLoader?.close()
            } catch (_: Exception) {
                // Keep the primary failure.
            }
            val message = error.message?.takeIf(String::isNotBlank)
                ?: error::class.simpleName
                ?: "Plugin loading failed"
            Log.e(TAG, "Could not load ${file.name}: $message")
            PluginLoadResult(file, error = message)
        }
    }

    private fun instantiatePlugin(
        classLoader: ClassLoader,
        className: String,
        declaredByManifest: Boolean,
    ): BasePlugin? {
        val clazz = try {
            classLoader.loadClass(className)
        } catch (_: ClassNotFoundException) {
            return null
        } catch (_: LinkageError) {
            return null
        }

        if (!BasePlugin::class.java.isAssignableFrom(clazz) ||
            clazz.isInterface ||
            Modifier.isAbstract(clazz.modifiers)
        ) {
            return null
        }
        if (!declaredByManifest && clazz.getAnnotation(CloudstreamPlugin::class.java) == null) {
            return null
        }

        return try {
            val constructor = clazz.getDeclaredConstructor()
            if (!constructor.canAccess(null)) constructor.isAccessible = true
            constructor.newInstance() as BasePlugin
        } catch (error: ReflectiveOperationException) {
            if (declaredByManifest) {
                throw PluginLoadException(
                    "Could not instantiate manifest plugin '$className': ${error.cause?.message ?: error.message}",
                    error,
                )
            }
            null
        } catch (error: LinkageError) {
            if (declaredByManifest) {
                throw PluginLoadException("Could not link manifest plugin '$className': ${error.message}", error)
            }
            null
        }
    }

    private fun removeContributions(path: String) {
        APIHolder.allProviders.withLock {
            APIHolder.allProviders.filter { it.sourcePlugin == path }.toList()
        }.forEach { provider ->
            APIHolder.allProviders.remove(provider)
            APIHolder.removePluginMapping(provider)
        }
        extractorApis.withLock {
            extractorApis.filter { it.sourcePlugin == path }.toList()
        }.forEach(extractorApis::remove)
    }

    private fun inspectArchive(file: File): PluginArchiveInfo = JarFile(file).use { archive ->
        val entries = archive.entries().asSequence().toList()
        val classNames = entries.asSequence()
            .filterNot { it.isDirectory }
            .map { it.name }
            .filter { it.endsWith(".class") }
            .filterNot { it.startsWith("META-INF/versions/") || it == "module-info.class" }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .toList()

        val manifestClassName = archive.getJarEntry("manifest.json")?.let { entry ->
            archive.getInputStream(entry).use { stream ->
                objectMapper.readValue(stream, BasePlugin.Manifest::class.java).pluginClassName
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
        }

        PluginArchiveInfo(
            classNames = classNames,
            manifestClassName = manifestClassName,
            containsDex = entries.any { it.name == "classes.dex" },
        )
    }

    private data class PluginArchiveInfo(
        val classNames: List<String>,
        val manifestClassName: String?,
        val containsDex: Boolean,
    )

    private class PluginLoadException(message: String, cause: Throwable) : Exception(message, cause)
}
