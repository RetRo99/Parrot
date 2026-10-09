package com.retro99.server.api

/**
 * The host of [link] when following it leaves the catalogue at [catalogueAddress] for a device
 * on the local network, else null. A screen asks before following such a link (plan §4,
 * "Open a device on your network?"). A catalogue that itself lives on the local network links
 * to its own origin freely.
 *
 * The transport marks the same case on the response it fetched; this is the question a screen
 * can ask before any request is made.
 */
fun localNetworkHostLeaving(catalogueAddress: String, link: String): String? {
    val target = originOf(link) ?: return null
    if (target == originOf(catalogueAddress)) return null
    return target.host.takeIf(::isLocalNetworkHost)
}

private data class Origin(val scheme: String, val host: String, val port: String)

private fun originOf(url: String): Origin? {
    val scheme = url.substringBefore("://", "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
    val bracketed = authority.startsWith("[")
    val host = if (bracketed) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    val port = if (bracketed) authority.substringAfter("]:", "") else authority.substringAfter(':', "")
    if (host.isEmpty() || host == "]") return null
    return Origin(scheme, host.lowercase().trimEnd('.'), port.ifEmpty { if (scheme == "https") "443" else "80" })
}

private fun isLocalNetworkHost(host: String): Boolean {
    val name = host.removeSurrounding("[", "]")
    if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".lan")) return true
    if (':' in name) {
        // An IPv4 address written inside an IPv6 one (::ffff:192.168.1.20).
        if ('.' in name) return name.startsWith("::ffff:") && isLocalIpv4(name.substringAfterLast(':'))
        if (name == "::1" || name == "::") return true
        val first = name.substringBefore(':').toIntOrNull(16) ?: return false
        // fc00::/7 (unique local) and fe80::/10 (link local).
        return first and 0xfe00 == 0xfc00 || first and 0xffc0 == 0xfe80
    }
    return isLocalIpv4(name)
}

private fun isLocalIpv4(address: String): Boolean {
    val octets = address.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] in setOf(0, 10, 127) || octets[0] == 172 && octets[1] in 16..31 ||
        octets[0] == 192 && octets[1] == 168 || octets[0] == 169 && octets[1] == 254
}
