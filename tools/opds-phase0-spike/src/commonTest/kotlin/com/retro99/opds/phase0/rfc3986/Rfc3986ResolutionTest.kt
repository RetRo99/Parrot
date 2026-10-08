package com.retro99.opds.phase0.rfc3986

import com.retro99.opds.phase0.platformTag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC 3986 §5.4.1/§5.4.2 resolution vectors, plus catalogue-relevant extras:
 * relative-link semantics with effective-response URLs, meaningful trailing
 * slash, query-only references, and root resource URLs without a path
 * (§7 Phase 0: "URL resolution against RFC 3986 vectors"). Vectors quoted from
 * the RFC (license note: tools/opds-phase0-spike/README.md).
 */
class Rfc3986ResolutionTest {

    @Test
    fun `normal_examples_RFC_3986_5_4_1`() {
        resolveAll(BASE, RFC_5_4_1_NORMAL)
    }

    @Test
    fun `abnormal_examples_RFC_3986_5_4_2_strict_parser`() {
        resolveAll(BASE, RFC_5_4_2_ABNORMAL_STRICT)
    }

    @Test
    fun `catalogue_link_cases`() {
        resolveAll(
            "https://catalogue.example.org/opds/",
            listOf(
                // Relative links from the effective response URL (after redirects).
                "treatise" to "https://catalogue.example.org/opds/treatise",
                "./treatise" to "https://catalogue.example.org/opds/treatise",
                "sub/feed.opds" to "https://catalogue.example.org/opds/sub/feed.opds",
                // Meaningful trailing slash is preserved on directory merges.
                "a/" to "https://catalogue.example.org/opds/a/",
                "./" to "https://catalogue.example.org/opds/",
                // Previous/next links commonly use query-only references.
                "?offset=25" to "https://catalogue.example.org/opds/?offset=25",
                // Root-path links and scheme-relative links resolve to the origin.
                "/sitemap.xml" to "https://catalogue.example.org/sitemap.xml",
                "//mirror.example.org/opds" to "https://mirror.example.org/opds",
                // Dot segments are eliminated outside their base directory.
                "../root.opds" to "https://catalogue.example.org/root.opds",
            ),
        )
    }

    @Test
    fun `base_urls_without_a_path_and_with_an_existing_query`() {
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
                // A non-empty relative path forgets the base query.
                "related" to "https://feeds.example.org/related",
            ),
        )
    }

    private fun resolveAll(base: String, cases: List<Pair<String, String>>) {
        cases.forEachIndexed { index, (reference, expected) ->
            assertEquals(
                expected = expected,
                actual = ReferenceResolver.resolve(base, reference),
                message = "${platformTag}: vector #$index (base='$base', ref='$reference')",
            )
        }
    }

    companion object {
        private const val BASE = "http://a/b/c/d;p?q"

        /** RFC 3986 §5.4.1. */
        private val RFC_5_4_1_NORMAL = listOf(
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
        )

        /** RFC 3986 §5.4.2, expected under the recommended strict parsing. */
        private val RFC_5_4_2_ABNORMAL_STRICT = listOf(
            "http:g" to "http:g",
            "http:/g" to "http:/g",
            "http://g" to "http://g",
            "./../g" to "http://a/b/g",
        )
    }
}
