package com.retro99.server.api

/** Shared position wording on the reader prompt, positions panel and apply sheet. */
fun InstallationDeviceIdentity.positionDeviceName(): String = try {
    selfReferenceName().ifBlank { "This device" }
} catch (_: Exception) {
    "This device"
}

/**
 * The same name inside a sentence: "this phone", "this tablet", "this iPhone", or "this device"
 * when the platform gives none. Catalogue copy uses it wherever the design says "this phone".
 */
fun InstallationDeviceIdentity.inSentenceDeviceName(): String =
    positionDeviceName().replaceFirstChar { it.lowercase() }
