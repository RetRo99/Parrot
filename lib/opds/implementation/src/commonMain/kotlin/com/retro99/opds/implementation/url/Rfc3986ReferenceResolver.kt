package com.retro99.opds.implementation.url

import com.retro99.opds.api.OpdsUrlResolver

/**
 * RFC 3986 reference resolution (§5.2/§5.2.4 merge + dot-segment removal),
 * ported from the Phase 0 spike where it was verified against the RFC §5.4
 * vector tables on Android and iOS.
 *
 * Deliberately small: authorities/paths are opaque (no userinfo split, no
 * percent normalization — encoded delimiters must stay opaque, plan §4), and
 * fragments are parsed only so they do not corrupt path/query composition.
 */
internal class Rfc3986ReferenceResolver : OpdsUrlResolver {

    private data class Url(
        val scheme: String,
        val authority: String?,
        val path: String,
        val query: String?,
        val fragment: String?,
    ) {
        fun compose(): String = buildString {
            append(scheme).append(':')
            if (authority != null) append("//").append(authority)
            append(path)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }
    }

    override fun schemeOf(url: String): String? = parseFastScheme(url)

    override fun resolve(base: String, reference: String): String {
        val b = parse(base)
        require(b.scheme.isNotEmpty()) { "base '$base' is not absolute" }
        val r = parse(reference)

        val target = when {
            r.scheme.isNotEmpty() -> Url(r.scheme, r.authority, removeDotSegments(r.path), r.query, r.fragment)
            r.authority != null ->
                Url(b.scheme, r.authority, removeDotSegments(r.path), r.query, r.fragment)
            r.path.isEmpty() && r.query == null ->
                // Empty or fragment-only reference: keep base path and query.
                Url(b.scheme, b.authority, b.path, b.query, r.fragment)
            r.path.isEmpty() ->
                // Query-only reference (e.g. pagination "?offset=25").
                Url(b.scheme, b.authority, b.path, r.query, r.fragment)
            r.path.startsWith("/") ->
                Url(b.scheme, b.authority, removeDotSegments(r.path), r.query, r.fragment)
            else ->
                Url(b.scheme, b.authority, removeDotSegments(merge(b, r.path)), r.query, r.fragment)
        }
        return target.compose()
    }

    /** RFC 3986 §5.2.3 merge. */
    private fun merge(base: Url, path: String): String = when {
        base.path.isEmpty() -> "/" + path
        else -> base.path.substringBeforeLast('/') + "/" + path
    }

    /** RFC 3986 §5.2.4 with the whole path as its input buffer. */
    private fun removeDotSegments(path: String): String {
        var input = path
        val output = StringBuilder()

        fun pop() {
            val lastSlash = output.lastIndexOf('/')
            if (lastSlash >= 0) output.deleteRange(lastSlash, output.length)
        }

        while (true) {
            when {
                input.startsWith("../") -> input = input.removePrefix("../")
                input.startsWith("./") -> input = input.removePrefix("./")
                input.startsWith("/./") -> input = "/" + input.removePrefix("/./")
                input == "/." -> input = "/"
                input.startsWith("/../") -> { input = "/" + input.removePrefix("/../"); pop() }
                input == "/.." -> { input = "/"; pop() }
                input == "." || input == ".." -> input = ""
                input.isEmpty() -> break
                else -> {
                    // Move the first path segment (initial "/" plus characters
                    // up to, but excluding, the next "/") from input to output.
                    val next = input.indexOf('/', 1)
                    val bound = if (next >= 0) next else input.length
                    output.appendRange(input, 0, bound)
                    input = input.substring(bound)
                }
            }
        }
        return output.toString()
    }

    /** RFC 3986 §3, simplified: opaque authority/path, no percent validation. */
    private fun parse(text: String): Url {
        val hash = text.indexOf('#')
        val fragment = if (hash >= 0) text.substring(hash + 1) else null
        val main = if (hash >= 0) text.substring(0, hash) else text

        var scheme = ""
        var rest = main
        var index = 0
        var scanning = true
        while (scanning && index < main.length) {
            val c = main[index]
            when {
                c == ':' && index == 0 -> { scanning = false }
                c == ':' -> { scheme = main.substring(0, index); rest = main.substring(index + 1); scanning = false }
                c == '/' || c == '?' || c == '#' -> { scanning = false }
                index == 0 && !c.isAsciiLetter() -> { scanning = false }
                index > 0 && !(c.isAsciiLetter() || c.isAsciiDigit() || c in "+-.") -> { scanning = false }
                else -> index++
            }
        }

        var authority: String? = null
        var authorityAndPath: String
        if (rest.startsWith("//")) {
            val nextSeparator = rest.drop(2).indexOfFirst { it == '/' || it == '?' || it == '#' }
            authority = if (nextSeparator >= 0) rest.substring(2, 2 + nextSeparator) else rest.substring(2)
            authorityAndPath = if (nextSeparator >= 0) rest.substring(2 + nextSeparator) else ""
        } else {
            authorityAndPath = rest
        }

        val queryIndex = authorityAndPath.indexOf('?')
        val path = if (queryIndex >= 0) authorityAndPath.substring(0, queryIndex) else authorityAndPath
        val query = if (queryIndex >= 0) authorityAndPath.substring(queryIndex + 1) else null
        return Url(scheme, authority?.ifEmpty { null }, path, query, fragment)
    }

    private fun parseFastScheme(url: String): String? {
        var index = 0
        while (index < url.length) {
            val c = url[index]
            when {
                c == ':' -> {
                    if (index == 0) return null
                    val scheme = url.substring(0, index)
                    return if (scheme.all { it.isAsciiLetter() || (index > 0 && (it.isAsciiDigit() || it in "+-.")) }) scheme else null
                }
                c == '/' || c == '?' || c == '#' -> return null
                else -> index++
            }
        }
        return null
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
