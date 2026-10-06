package com.retro99.server.api

/** Shared position wording on the reader prompt, positions panel and apply sheet. */
fun InstallationDeviceIdentity.positionDeviceName(): String = try {
    selfReferenceName().ifBlank { "This device" }
} catch (_: Exception) {
    "This device"
}
