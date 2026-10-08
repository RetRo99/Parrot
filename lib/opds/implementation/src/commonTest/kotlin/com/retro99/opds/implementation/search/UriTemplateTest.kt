package com.retro99.opds.implementation.search

import com.retro99.opds.api.OpdsTemplateExpander
import kotlin.test.*

/** Scalar vector tables from RFC 6570 §§3.2.2, 3.2.3, 3.2.8, 3.2.9.
 * IETF TRD §3.8 reproduction terms: https://trustee.ietf.org/license-info/ */
class UriTemplateTest {
    private val expander = Rfc6570Expander()
    private val variables = mapOf("var" to "value", "hello" to "Hello World!", "half" to "50%", "empty" to "", "x" to "1024", "y" to "768", "base" to "http://example.com/home/", "path" to "/foo", "who" to "fred")
    private fun vectors(vararg cases: Pair<String, String>) = cases.forEach { (template, expected) ->
        assertEquals(expected, expander.expand(template, variables), template)
    }
    @Test fun simple_vector_table() = vectors(
        "{var}" to "value", "{hello}" to "Hello%20World%21", "{half}" to "50%25", "O{empty}X" to "OX", "O{undef}X" to "OX",
        "{x,y}" to "1024,768", "{x,hello,y}" to "1024,Hello%20World%21,768", "?{x,empty}" to "?1024,", "?{x,undef}" to "?1024", "?{undef,y}" to "?768",
        "{base}" to "http%3A%2F%2Fexample.com%2Fhome%2F", "{path}/here" to "%2Ffoo/here")
    @Test fun reserved_vector_table() = vectors(
        "{+var}" to "value", "{+hello}" to "Hello%20World!", "{+half}" to "50%25", "{+base}index" to "http://example.com/home/index",
        "{+path}/here" to "/foo/here", "{+x,hello,y}" to "1024,Hello%20World!,768")
    @Test fun query_vector_table() = vectors(
        "{?who}" to "?who=fred", "{?half}" to "?half=50%25", "{?x,y}" to "?x=1024&y=768", "{?x,y,empty}" to "?x=1024&y=768&empty=",
        "{?x,y,undef}" to "?x=1024&y=768", "{?undef}" to "", "{?empty}" to "?empty=",
        "?fixed=yes{&x}" to "?fixed=yes&x=1024", "{&x,y,empty}" to "&x=1024&y=768&empty=", "{&undef}" to "")
    @Test fun unicode_reserved_and_percent_encoding() {
        assertEquals("?query=caf%C3%A9%20%F0%9F%A6%9C%20%26%2F%3F%23%25%2B", expander.expand("{?query}", mapOf("query" to "café 🦜 &/?#%+")))
        assertEquals("%D0%BE%D0%BD", expander.expand("{name}", mapOf("name" to "он")))
        assertEquals("a%2Fb", expander.expand("{+value}", mapOf("value" to "a%2Fb")))
        assertEquals("a%252Fb", expander.expand("{value}", mapOf("value" to "a%2Fb")))
        assertEquals("caf%C3%A9/%7Bvalue%7D", expander.expand("café/%7Bvalue%7D", emptyMap()))
        assertEquals("?%66oo=value", expander.expand("{?%66oo}", mapOf("%66oo" to "value")))
    }
    @Test fun unsupported_operators_modifiers_and_invalid_syntax_fail() {
        for (template in listOf("{#x}", "{.x}", "{/x}", "{;x}", "{@x}", "{|x}", "{!x}", "{,x}", "{=x}", "{$" + "x}", "{x*}", "{x:2}", "{}", "{?}", "{x,}", "{x(bad)}", "{x y}", "{x", "x}", "{{x}}", "{%}", "{%2}", "{%GG}", "{x..y}", "{.bad}", "a%GG", "a b", "a\\b")) {
            assertFailsWith<OpdsTemplateExpander.OpdsTemplateException>(template) { expander.expand(template, variables) }
        }
    }
    @Test fun error_messages_do_not_echo_private_templates() {
        val error = assertFailsWith<OpdsTemplateExpander.OpdsTemplateException> { expander.expand("https://example.org/PRIVATE?token=SECRET{/x}", variables) }
        assertFalse(error.message.orEmpty().contains("SECRET"))
        assertFalse(error.message.orEmpty().contains("PRIVATE"))
    }
    @Test fun template_detection() {
        assertTrue(expander.isTemplate("{?query}"))
        assertFalse(expander.isTemplate("/feed?literal=%7Bquery%7D"))
    }
}
