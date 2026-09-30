package com.retro99.settings.ui.servers

/** Adds `https://` when the user typed a bare host, and drops trailing slashes. */
internal fun normalizeServerAddress(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}

/** Host and port without the scheme, for display. */
internal fun serverHost(baseUrl: String): String =
    baseUrl.substringAfter("://").substringBefore('/').ifBlank { baseUrl }

internal fun isEncryptedAddress(baseUrl: String): Boolean =
    baseUrl.trim().startsWith("https://", ignoreCase = true)

internal fun isValidServerAddress(input: String): Boolean {
    val host = serverHost(normalizeServerAddress(input))
    return input.isNotBlank() && host.isNotBlank() && !host.contains(' ')
}
