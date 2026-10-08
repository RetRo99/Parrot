package com.retro99.opds.phase0.rfc6570

import com.retro99.opds.phase0.platformTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * RFC 6570 vectors for the declared supported level (§4: `query` plus simple
 * expansion), plus OPDS2 search shapes; unsupported expressions fail
 * explicitly.
 */
class Rfc6570ExpansionTest {

    private data class Case(
        val template: String,
        val variables: Map<String, String>,
        val expected: String,
    )

    private fun case(template: String, vararg variables: Pair<String, String>, expected: String) =
        Case(template, variables.toMap(), expected)

    @Test
    fun simple_string_expansion_vectors() {
        expandAll(
            listOf(
                // RFC 6570 §3.2.2 table vectors.
                case("{var}", "var" to "value", expected = "value"),
                case("{hello}", "hello" to "Hello World!", expected = "Hello%20World%21"),
                case("{half}", "half" to "50%", expected = "50%25"),
                case("O{empty}X", "empty" to "", expected = "OX"),
                case("O{undef}X", expected = "OX"),
                case("{x,y}", "x" to "1024", "y" to "768", expected = "1024,768"),
                case("{x,hello,y}", "x" to "1024", "hello" to "Hello World!", "y" to "768", expected = "1024,Hello%20World%21,768"),
                case("?{x,empty}", "empty" to "", "x" to "1024", expected = "?1024,"),
                case("?{x,undef}", "x" to "1024", expected = "?1024"),
                case("?{undef,y}", "y" to "768", expected = "?768"),
                case("{base}", "base" to "http://example.com/home/", expected = "http%3A%2F%2Fexample.com%2Fhome%2F"),
                case("{path}/here", "path" to "/foo", expected = "%2Ffoo/here"),
                // Unicode multi-byte percent-encoding via UTF-8.
                case("{name}", "name" to "он", expected = "%D0%BE%D0%BD"),
            ),
        )
    }

    @Test
    fun reserved_expansion_keeps_separators_literal() {
        expandAll(
            listOf(
                // RFC 6570 §3.2.3 table vectors.
                case("{+var}", "var" to "value", expected = "value"),
                case("{+hello}", "hello" to "Hello World!", expected = "Hello%20World!"),
                // A lone '%' is still encoded; only real pct-triplets pass through.
                case("{+half}", "half" to "50%", expected = "50%25"),
                // RFC §3.2.1: pct-encoded triplets pass through only in reserved
                // expansion ("the percent character is only allowed as part of
                // a pct-encoded triplet" for '+' expansion).
                case("{+triplet}", "triplet" to "a%2Fb", expected = "a%2Fb"),
                case("{triplet}", "triplet" to "a%2Fb", expected = "a%252Fb"),
            ),
        )
    }

    @Test
    fun form_style_query_expansion_vectors() {
        expandAll(
            listOf(
                // RFC 6570 §3.2.8 table vectors.
                case("{?who}", "who" to "fred", expected = "?who=fred"),
                case("{?half}", "half" to "50%", expected = "?half=50%25"),
                case("{?x,y}", "x" to "1024", "y" to "768", expected = "?x=1024&y=768"),
                case("{?x,y,empty}", "x" to "1024", "y" to "768", "empty" to "", expected = "?x=1024&y=768&empty="),
                case("{?x,y,undef}", "x" to "1024", "y" to "768", expected = "?x=1024&y=768"),
                // Continuation form (§3.2.9).
                case("?fixed=yes{&x}", "x" to "1024", expected = "?fixed=yes&x=1024"),
                // Variable names are subject to the same encoding rules.
                case("{?q}", "q" to "a&b", expected = "?q=a%26b"),
                case("{?q}", "q" to "café", expected = "?q=caf%C3%A9"),
            ),
        )
    }

    @Test
    fun spaces_are_handled_the_same_way_on_both_targets() {
        expandAll(
            listOf(
                case("{name}", "name" to "a b", expected = "a%20b"),
                case("{+name}", "name" to "a b", expected = "a%20b"),
                case("{?q}", "q" to " ", expected = "?q=%20"),
            ),
        )
    }

    @Test
    fun opds2_search_shapes() {
        expandAll(
            listOf(
                // The shared fixture link: {rel: "search", href: "{?query,title}"}
                case("{?query,title}", "query" to "river", expected = "?query=river"),
                // An OPDS-style template appended to an existing query string.
                case("?opts=1{&q}", "q" to "x", expected = "?opts=1&q=x"),
            ),
        )
    }

    @Test
    fun unsupported_expression_forms_fail_explicitly() {
        val variables = mapOf("who" to "fred", "half" to "50%", "x" to "1024")
        listOf(
            "{;x}", "{#x}", "{.who}", "{/x}", "{@x}", "{|x}", "{!x}", "{,x}",
            "{x,y*}", "{x:2}", "{=x}", "{x(bad)}", "a}b", "{ ", "{x y}",
        ).forEach { template ->
            assertFailsWith<Rfc6570Spike.UnsupportedTemplateException>(
                "${platformTag}: '${template}' must fail explicitly",
            ) {
                Rfc6570Spike.expand(template, variables)
                error("unreachable")
            }
        }
    }

    private fun expandAll(cases: List<Case>) {
        cases.forEach { spec ->
            assertEquals(
                spec.expected,
                Rfc6570Spike.expand(spec.template, spec.variables),
                "${platformTag}: template '${spec.template}'",
            )
        }
    }
}
