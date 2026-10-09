package com.retro99.server.api

/*
 * How a catalogue's address is shown and logged (CATALOGUE_PROMPT §4 and §9 B6, plan §10.6).
 * Some catalogues carry a key in the address itself, so the whole address is treated as
 * possibly secret. One rule decides which parts look like a key; the settings screen, the
 * Get books and Libraries rows, and log lines all use it.
 */

private const val SETTINGS_MASK = "••••••••"
private const val ROW_MASK = "••••"
private const val LOG_MASK = "[redacted]"
private const val MIN_RANDOM_KEY_LENGTH = 20

/** Names that mark a query value, or the path part after them, as a key. */
private val KEY_NAMES = setOf("apikey", "token", "key", "auth", "password")
private val KEY_NAME_ENDINGS = listOf("apikey", "token", "password")

private fun isKeyName(name: String): Boolean {
    val plain = name.lowercase().filter { it != '_' && it != '-' }
    return plain in KEY_NAMES || KEY_NAME_ENDINGS.any { plain.endsWith(it) }
}

/**
 * 20 or more characters that read as generated, not written: only hexadecimal digits, or
 * letters and digits mixed inside one run (a UUID, a base64 key). Words joined by dashes,
 * with or without a year, are not.
 */
private fun looksRandom(value: String): Boolean {
    if (value.length < MIN_RANDOM_KEY_LENGTH) return false
    if (!value.all { it.isAsciiLetterOrDigit() || it in "._~+=-" }) return false
    val alphanumeric = value.filter { it.isAsciiLetterOrDigit() }
    if (alphanumeric.length >= MIN_RANDOM_KEY_LENGTH && alphanumeric.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return true
    var changes = 0
    var mostInOneRun = 0
    var inThisRun = 0
    var previous: Char? = null
    for (c in value) {
        if (!c.isAsciiLetterOrDigit()) {
            previous = null
            inThisRun = 0
            continue
        }
        if (previous != null && previous.isDigit() != c.isDigit()) {
            changes++
            inThisRun++
            mostInOneRun = maxOf(mostInOneRun, inThisRun)
        }
        previous = c
    }
    return changes >= 3 || mostInOneRun >= 2
}

private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private class AddressParts(
    val scheme: String, // "https://", or "" when the address was typed without one
    val userInfo: String?,
    val host: String,
    val port: String?,
    val path: String, // "" or starts with '/'
    val query: String?, // without '?'
    val fragment: String?, // without '#'
)

private fun parts(address: String): AddressParts {
    val schemeEnd = address.indexOf("://")
    // "://" only counts before the path starts.
    val hasScheme = schemeEnd > 0 && address.substring(0, schemeEnd).none { it == '/' || it == '?' || it == '#' }
    val scheme = if (hasScheme) address.substring(0, schemeEnd + 3) else ""
    var rest = address.substring(scheme.length)
    val fragment = rest.indexOf('#').takeIf { it >= 0 }?.let { at -> rest.substring(at + 1).also { rest = rest.substring(0, at) } }
    val query = rest.indexOf('?').takeIf { it >= 0 }?.let { at -> rest.substring(at + 1).also { rest = rest.substring(0, at) } }
    val pathStart = rest.indexOf('/')
    val path = if (pathStart >= 0) rest.substring(pathStart) else ""
    var authority = if (pathStart >= 0) rest.substring(0, pathStart) else rest
    val userInfo = authority.lastIndexOf('@').takeIf { it >= 0 }?.let { at -> authority.substring(0, at).also { authority = authority.substring(at + 1) } }
    val portStart = authority.lastIndexOf(':').takeIf { it > authority.lastIndexOf(']') }
    val port = portStart?.let { authority.substring(it + 1) }
    val host = if (portStart != null) authority.substring(0, portStart) else authority
    return AddressParts(scheme, userInfo, host, port, path, query, fragment)
}

/** [path] with each part that looks like a key replaced by [mask]. Separators are kept as they were. */
private fun maskPath(path: String, mask: String): String {
    val segments = path.split('/')
    return segments.mapIndexed { index, segment ->
        val afterKeyName = index > 0 && segments[index - 1].let { it.isNotEmpty() && it.lowercase() in KEY_NAMES }
        if (segment.isNotEmpty() && (afterKeyName || looksRandom(segment))) mask else segment
    }.joinToString("/")
}

private fun maskQuery(query: String, mask: String, everyValue: Boolean): String = query.split('&').joinToString("&") { pair ->
    val equals = pair.indexOf('=')
    if (equals < 0) return@joinToString pair
    val name = pair.substring(0, equals)
    val value = pair.substring(equals + 1)
    if (value.isNotEmpty() && (everyValue || isKeyName(name) || looksRandom(value))) "$name=$mask" else pair
}

/**
 * The address for the catalogue's settings screen: every part that looks like a key becomes
 * "••••••••". That is a query value named apikey, token, key, auth or password (or ending
 * in one of the longer three, like `access_token`), a path part that follows one of those
 * names, and any path part or query value of 20 or more random-looking characters. Everything
 * else is returned exactly as it was typed. The edit dialog shows the address itself.
 */
fun maskAddress(url: String): String {
    val p = parts(url)
    return buildString {
        append(p.scheme)
        p.userInfo?.let { info ->
            val colon = info.indexOf(':')
            append(if (colon >= 0 && colon < info.length - 1) info.substring(0, colon + 1) + SETTINGS_MASK else info)
            append('@')
        }
        append(p.host)
        p.port?.let { append(':').append(it) }
        append(maskPath(p.path, SETTINGS_MASK))
        p.query?.let { append('?').append(maskQuery(it, SETTINGS_MASK, everyValue = false)) }
        p.fragment?.let { append('#').append(it) }
    }
}

/** Whether [maskAddress] hides anything, which is when settings offers "Show". */
fun addressHasKey(url: String): Boolean = maskAddress(url) != url

private fun displayHost(p: AddressParts): String {
    val host = p.host.lowercase()
    val isName = host.isNotEmpty() && host.all { it.isAsciiLetterOrDigit() || it == '.' || it == '-' }
    val isBracketedIp = host.length > 2 && host.startsWith('[') && host.endsWith(']') &&
        host.substring(1, host.length - 1).all { it.isAsciiLetterOrDigit() || it == ':' || it == '.' }
    return if (isName || isBracketedIp) host else ""
}

private fun effectivePort(p: AddressParts): String =
    p.port?.takeIf { port -> port.isNotEmpty() && port.all { it.isDigit() } }
        ?: if (p.scheme.equals("http://", ignoreCase = true)) "80" else "443"

/**
 * The address line of a catalogue's row on Get books and in Libraries.
 *
 * The host alone, unless another catalogue in [allSources] has the same host. Then the host
 * and path, with parts that look like a key as "••••". If that is still the same as another
 * catalogue's, the port is added. If even that is the same, the host alone again: the
 * catalogues' names tell them apart. A query string is never part of a row. Empty when the
 * address has no readable host.
 */
fun rowAddress(source: ServerConfig, allSources: List<ServerConfig>): String {
    val own = parts(source.baseUrl)
    val host = displayHost(own)
    if (host.isEmpty()) return ""
    val others = allSources
        .filter { it.type == ServerType.Opds && it.id != source.id }
        .map { parts(it.baseUrl) }
        .filter { displayHost(it) == host }
    if (others.isEmpty()) return host
    fun withPath(p: AddressParts) = host + maskPath(p.path, ROW_MASK).trimEnd('/')
    fun withPort(p: AddressParts) = "$host:${effectivePort(p)}" + maskPath(p.path, ROW_MASK).trimEnd('/')
    return listOf(::withPath, ::withPort)
        .firstOrNull { shown -> others.none { shown(it) == shown(own) } }
        ?.invoke(own)
        ?: host
}

/**
 * The address for a log line or an analytics value, by the same rule and stricter: parts
 * that look like a key and every query value become "[redacted]", and a user name, password
 * or fragment in the address is dropped. Plan §10.6 still prefers no address at all where a
 * catalogue's name or id will do.
 */
fun redactAddress(url: String): String {
    val p = parts(url)
    return buildString {
        append(p.scheme)
        append(p.host)
        p.port?.let { append(':').append(it) }
        append(maskPath(p.path, LOG_MASK))
        p.query?.let { append('?').append(maskQuery(it, LOG_MASK, everyValue = true)) }
    }
}
