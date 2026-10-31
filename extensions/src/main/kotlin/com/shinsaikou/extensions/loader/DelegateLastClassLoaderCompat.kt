package com.shinsaikou.extensions.loader

import android.os.Build
import dalvik.system.DelegateLastClassLoader
import dalvik.system.PathClassLoader
import java.net.URL
import java.util.Collections
import java.util.Enumeration

/**
 * The classloader every extension is loaded through. Resolution order is boot, then the
 * extension's own dex, then the host: extension bytecode links `eu.kanade.tachiyomi.*`, OkHttp,
 * Jsoup and Injekt against the host's single copy, while a class the extension bundles itself wins
 * over a same-named host class.
 *
 * Not an upstream v0.20.4 file. It comes from the loader research app, which itself ports an upstream
 * commit made after v0.20.4; v0.20.4 ships `ChildFirstPathClassLoader`, which has the same
 * resolution order and is not vendored. The package is fresh because the file is not v0.20.4 code.
 */
@Suppress("FunctionName")
fun DelegateLastClassLoaderCompat(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader,
): ClassLoader {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        DelegateLastClassLoader(dexPath, librarySearchPath, parent)
    } else {
        DelegateLastPathClassLoader(dexPath, librarySearchPath, parent)
    }
}

/** Backport of [DelegateLastClassLoader], which was added in API 27. */
private class DelegateLastPathClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    parent: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, parent) {

    private val bootClassLoader: ClassLoader? = Any::class.java.classLoader

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        findLoadedClass(name)?.let { return it }

        if (bootClassLoader != null) {
            try {
                return bootClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }

        val fromSuper = try {
            return findClass(name)
        } catch (e: ClassNotFoundException) {
            e
        }

        return try {
            parent.loadClass(name)
        } catch (_: ClassNotFoundException) {
            throw fromSuper
        }
    }

    override fun getResource(name: String?): URL? {
        return bootClassLoader?.getResource(name)
            ?: findResource(name)
            ?: parent?.getResource(name)
    }

    override fun getResources(name: String?): Enumeration<URL> {
        val resources = buildList {
            bootClassLoader?.getResources(name)?.let { addAll(it.toList()) }
            findResources(name)?.let { addAll(it.toList()) }
            parent?.getResources(name)?.let { addAll(it.toList()) }
        }
        return Collections.enumeration(resources)
    }
}
