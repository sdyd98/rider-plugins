package com.example.xlsx

/** Marker whose class loader is this module's — i.e. the host plugin's loader once bundled. */
private object PoiClassLoaderHolder

/**
 * Apache POI relies on the *thread context class loader* for XMLBeans schema lookup and JAXP
 * (SAX/DOM) service discovery. Inside the IDE that class loader is NOT the plugin class loader,
 * which makes workbook parsing fail with ClassNotFound / ClassCastException errors.
 *
 * Run every POI call through this helper so the plugin class loader is active for the duration,
 * then restore the original loader. Lives in the shared `common` module (public so plugin modules
 * can call it); the marker's loader resolves to whichever plugin bundles this module.
 */
fun <T> withPoiClassLoader(block: () -> T): T {
    val thread = Thread.currentThread()
    val original = thread.contextClassLoader
    return try {
        thread.contextClassLoader = PoiClassLoaderHolder::class.java.classLoader
        block()
    } finally {
        thread.contextClassLoader = original
    }
}
