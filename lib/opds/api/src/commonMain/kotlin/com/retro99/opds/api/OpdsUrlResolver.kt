package com.retro99.opds.api

/**
 * RFC 3986 reference resolution (plan §4 "URLs, search, and navigation").
 * `lib/opds/implementation` provides the implementation verified against the
 * RFC §5.4 vectors.
 */
interface OpdsUrlResolver {
    fun resolve(base: String, reference: String): String
    /** The scheme of an absolute URL, or null; used for scheme-policy checks. */
    fun schemeOf(url: String): String?
}
