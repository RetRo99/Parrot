package com.retro99.opds.implementation.url

import com.retro99.opds.api.OpdsUrlResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RFC 3986 §5.4.1/§5.4.2 resolution vectors plus catalogue-relevant cases:
 * relative links resolve against the **effective response URL after
 * redirects**, meaningful trailing slash and query-only references are
 * preserved, root/scheme-relative/dot-segment forms resolve per the RFC
 * (plan §4; Phase 0 verified the behavior; Phase 1 holds the contract).
 */
class OpdsUrlResolverTest {

    private val resolver: OpdsUrlResolver = Rfc3986ReferenceResolver()

    private fun resolveAll(base: String, cases: List<Pair<String, String>>) {
        cases.forEachIndexed { index, (reference, expected) ->
            assertEquals(
                expected,
                resolver.resolve(base, reference),
                "vector #$index (base='$base', ref='$reference')",
            )
        }
    }

    @Test
    fun normal_examples_unicode_rfc_5_4_1() {
        resolveAll(
            "http://a/b/c/d;p?q",
            listOf(
                "g" to "http://a/b/c/g",
                "" to "http://a/b/c/d;p?q",
                "./g" to "http://a/b/c/g",
                "/g" to "http://a/g",
                "//g" to "http://g",
                "?y" to "http://a/b/c/d;p?y",
                "g?y" to "http://a/b/c/g?y",
                "#s" to "http://a/b/c/d;p?q#s",
                "g#s" to "http://a/b/c/g#s",
                "g?y#s" to "http://a/b/c/g?y#s",
                ";x" to "http://a/b/c/;x",
                "g;x" to "http://a/b/c/g;x",
                "g;x?y#s" to "http://a/b/c/g;x?y#s",
                "." to "http://a/b/c/",
                "./" to "http://a/b/c/",
                ".." to "http://a/b/",
                "../" to "http://a/b/",
                "../g" to "http://a/b/g",
                "../.." to "http://a/",
                "../../" to "http://a/",
                "../../g" to "http://a/g",
            ),
        )
    }

    @Test
    fun abnormal_examples_strict_parsing() {
        resolveAll(
            "http://a/b/c/d;p?q",
            listOf(
                // §5.4.2 under recommended strict parsing.
                "g:h" to "g:h",
                "http:g" to "http:g",
                "http:/g" to "http:/g",
                "http://g" to "http://g",
                "./../g" to "http://a/b/g",
            ),
        )
    }

    @Test
    fun catalogue_link_cases() {
        resolveAll(
            "https://catalogue.example.org/opds/",
            listOf(
                "treatise" to "https://catalogue.example.org/opds/treatise",
                "./treatise" to "https://catalogue.example.org/opds/treatise",
                "sub/feed.opds" to "https://catalogue.example.org/opds/sub/feed.opds",
                // Meaningful trailing slash preserved on directory merges.
                "a/" to "https://catalogue.example.org/opds/a/",
                "./" to "https://catalogue.example.org/opds/",
                // Pagination via query-only reference.
                "?offset=25" to "https://catalogue.example.org/opds/?offset=25",
                // Root-path links and scheme-relative links resolve to the origin.
                "/sitemap.xml" to "https://catalogue.example.org/sitemap.xml",
                "//mirror.example.org/opds" to "https://mirror.example.org/opds",
                "../root.opds" to "https://catalogue.example.org/root.opds",
                // Percent-encoded delimiters stay opaque; no re-encoding.
                "a%2Fb.opds" to "https://catalogue.example.org/opds/a%2Fb.opds",
            ),
        )
    }

    @Test
    fun base_urls_without_path_or_with_existing_query() {
        resolveAll(
            "https://feeds.example.org",
            listOf(
                "" to "https://feeds.example.org",
                "#top" to "https://feeds.example.org#top",
                "feed" to "https://feeds.example.org/feed",
                "?page=2" to "https://feeds.example.org?page=2",
                "feed?p=3" to "https://feeds.example.org/feed?p=3",
            ),
        )
        resolveAll(
            "https://feeds.example.org/feed?p=3",
            listOf(
                "" to "https://feeds.example.org/feed?p=3",
                "?p=4" to "https://feeds.example.org/feed?p=4",
                "#frag" to "https://feeds.example.org/feed?p=3#frag",
                // A non-empty path reference forgets the base query.
                "related" to "https://feeds.example.org/related",
            ),
        )
    }

    @Test
    fun scheme_detection() {
        assertEquals("https", resolver.schemeOf("https://x/y"))
        assertEquals(null, resolver.schemeOf("/relative"))
        assertNull(resolver.schemeOf("noscheme"))
        assertEquals("opds", resolver.schemeOf("OPDS://x/y".lowercase()))
    }

    @Test
    fun relative_resolution_never_inherits_a_feed_self_link_url() {
        // Plan §4: a feed's `self` must not change the base of its relative links —
        // the resolved base is the caller's job, verified by contrast.
        val self = "https://example.dev/mirror/feeds/root"
        val responseUrl = "https://example.dev/origin/feed"
        assertEquals(
            "https://example.dev/origin/relative.opds",
            resolver.resolve(responseUrl, "relative.opds"),
        )
        // The same relative reference against `self` lands elsewhere — so resolving
        // the whole feed against `self` (a bug) is observable and wrong:
        assertEquals(
            "https://example.dev/mirror/feeds/relative.opds",
            resolver.resolve(self, "relative.opds"),
        )
        // The self link's own path resolves against the response URL:
        assertEquals(
            "https://example.dev/mirror/feeds/root",
            resolver.resolve(responseUrl, "/mirror/feeds/root"),
        )
    }
}
