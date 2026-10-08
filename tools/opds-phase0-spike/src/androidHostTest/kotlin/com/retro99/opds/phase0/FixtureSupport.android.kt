package com.retro99.opds.phase0

internal actual fun readFixture(name: String): ByteArray {
    // androidHostTest runs on the host JVM; commonTest resources are packaged
    // next to androidHostTest classes.
    val stream = javaClassLoader.getResourceAsStream(name)
        ?: throw AssertionError("missing fixture resource '$name'")
    return stream.use { it.readBytes() }
}

private val javaClassLoader: ClassLoader =
    requireNotNull(object {}.javaClass.classLoader) { "no classloader available to androidHostTest" }

/** Tag recorded alongside observed behaviors in the Phase 0 report. */
internal actual val platformTag: String get() = "android-host"
