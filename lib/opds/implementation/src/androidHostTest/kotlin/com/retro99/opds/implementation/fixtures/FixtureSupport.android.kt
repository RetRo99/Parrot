package com.retro99.opds.implementation.fixtures

internal actual fun readFixture(name: String): ByteArray {
    val stream = javaClassLoader.getResourceAsStream(name)
        ?: throw AssertionError("missing fixture resource '$name'")
    return stream.use { it.readBytes() }
}

private val javaClassLoader: ClassLoader =
    requireNotNull(object {}.javaClass.classLoader) { "no classloader available to androidHostTest" }

internal actual val platformTag: String get() = "android-host"
