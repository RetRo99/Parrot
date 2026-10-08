package com.retro99.opds.phase0.rfc3986

/**
 * Phase 0 spike: a minimal RFC 3986 reference-resolution implementation,
 * verifying that hand-rolled resolution can be made to pass the RFC §5.4
 * vectors without platform API differences. Phase 1 will grow this (or replace
 * it) inside `lib/opds`; only the vectors are the durable contract.
 *
 * Deliberately small: authorities and paths are opaque (no userinfo split, no
 * percent-normalization), fragments are parsed and recomposed but paths and
 * queries are otherwise not validated. Malformed bases throw.
 */
object ReferenceResolver {

    private class Url(
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

    /** RFC 3986 §5.2.2, strict interpretation (no same-scheme exceptions). */
    fun resolve(base: String, reference: String): String {
        val b = parse(base)
        require(b.scheme.isNotEmpty()) { "base '$base' is not absolute" }
        val r = parse(reference)

        val target = when {
            r.scheme.isNotEmpty() -> {
                Url(r.scheme, r.authority, removeDotSegments(r.path), r.query, r.fragment)
            }
            r.authority != null -> {
                Url(b.scheme, r.authority, removeDotSegments(r.path), r.query, r.fragment)
            }
            r.path.isEmpty() && r.query == null -> {
                // Empty or fragment-only reference: keep base path and query.
                Url(b.scheme, b.authority, b.path, b.query, r.fragment)
            }
            r.path.isEmpty() -> {
                // Query-only reference (e.g. "?page=2"): base path, reference query.
                Url(b.scheme, b.authority, b.path, r.query, r.fragment)
            }
            r.path.startsWith("/") -> {
                Url(b.scheme, b.authority, removeDotSegments(r.path), r.query, r.fragment)
            }
            else -> {
                Url(b.scheme, b.authority, removeDotSegments(merge(b, r.path)), r.query, r.fragment)
            }
        }
        return target.compose()
    }

    /**
     * RFC 3986 §5.2.3 merge: with a base authority but empty base path the
     * merged path is the root directory; otherwise everything after the last
     * "/" of the base path is replaced.
     */
    private fun merge(base: Url, path: String): String = when {
        base.path.isEmpty() -> "/" + path
        else -> base.path.substringBeforeLast('/') + "/" + path
    }

    /** RFC 3986 §5.2.4 with the whole path as its input buffer. */
    private fun removeDotSegments(path: String): String {
        var input = path
        val output = StringBuilder()

        fun pop(): Unit = run {
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
                index == 0 && !c.isLetter() -> { scanning = false }
                index > 0 && !(c.isLetterOrDigit() || c in "+-.") -> { scanning = false }
                else -> index++
            }
        }

        val authorityAndPath: String
        val authority: String?
        if (rest.startsWith("//")) {
            val nextSep = rest.drop(2).indexOfFirst { it == '/' || it == '?' || it == '#' }
            authority = if (nextSep >= 0) rest.substring(2, 2 + nextSep) else rest.substring(2)
            authorityAndPath = if (nextSep >= 0) rest.substring(2 + nextSep) else ""
        } else {
            authority = null
            authorityAndPath = rest
        }

        val queryIndex = authorityAndPath.indexOf('?')
        val path = if (queryIndex >= 0) authorityAndPath.substring(0, queryIndex) else authorityAndPath
        val query = if (queryIndex >= 0) authorityAndPath.substring(queryIndex + 1) else null
        val finalAuthority = authority?.ifEmpty { null }

        return Url(scheme, finalAuthority, path, query, fragment)
    }
}
