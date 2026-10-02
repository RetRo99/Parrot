package com.retro99.login.ui.login

/** Turns what the user typed into a full server URL, and back into a short host to display. */
internal object ServerAddress {
    private const val HTTPS = "https://"
    private const val HTTP = "http://"

    /**
     * Adds `https://` when no scheme was typed, keeps a typed `http://` and strips trailing
     * slashes. Returns an empty string when there is no address yet.
     */
    fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.equals(HTTPS, ignoreCase = true) || trimmed.equals(HTTP, ignoreCase = true)) {
            return ""
        }
        val withScheme = if (trimmed.contains("://")) trimmed else HTTPS + trimmed
        return withScheme.trimEnd('/')
    }

    /**
     * `books.example.com:8001` for `https://books.example.com:8001/path`. Keeps
     * `http://` so an unencrypted server is never shown like a secure one.
     */
    fun displayHost(url: String): String {
        val authority = authority(url)
        return if (isInsecure(url)) HTTP + authority else authority
    }

    /** True for plain `http://` addresses, which send credentials unencrypted. */
    fun isInsecure(url: String): Boolean = url.trim().startsWith(HTTP, ignoreCase = true)

    private fun authority(url: String): String {
        return url.trim()
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    }

    fun isValid(url: String): Boolean {
        val trimmedUrl = url.trim()
        val schemeSeparator = trimmedUrl.indexOf("://")
        if (schemeSeparator <= 0) return false

        val scheme = trimmedUrl.substring(0, schemeSeparator).lowercase()
        if (scheme != "http" && scheme != "https") return false

        val authority = authority(trimmedUrl)
        if (authority.isBlank()) return false

        val host = when {
            authority.startsWith('[') -> authority.substringAfter('[').substringBefore(']')
            authority.count { char -> char == ':' } == 1 -> authority.substringBefore(':')
            else -> authority
        }

        return host.isNotBlank()
    }
}
