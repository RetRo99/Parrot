package com.retro99.catalogue.domain

import com.retro99.catalogue.domain.CatalogueDescriptionFormat.Html
import com.retro99.catalogue.domain.CatalogueDescriptionFormat.Text
import com.retro99.catalogue.domain.CatalogueDescriptionFormat.Xhtml
import com.retro99.catalogue.domain.CatalogueRichText.ListBlock
import com.retro99.catalogue.domain.CatalogueRichText.ListItem
import com.retro99.catalogue.domain.CatalogueRichText.Marker
import com.retro99.catalogue.domain.CatalogueRichText.Paragraph
import com.retro99.catalogue.domain.CatalogueRichText.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CATALOGUE_PROMPT §1: paragraphs, line breaks, bold, italic, lists. Nothing else survives. */
class CatalogueDescriptionSanitizerTest {
    private fun html(markup: String) = sanitizeCatalogueDescription(markup, Html)
    private fun p(vararg spans: Span) = Paragraph(spans.toList())
    private fun p(text: String) = Paragraph(listOf(Span(text)))
    private fun bullet(text: String, level: Int = 0) = ListItem(listOf(Span(text)), level, Marker.Bullet)
    private fun number(n: Int, text: String, level: Int = 0) = ListItem(listOf(Span(text)), level, Marker.Number(n))
    private fun CatalogueRichText.plain(): String = blocks.joinToString("\n\n") { block ->
        when (block) {
            is Paragraph -> block.spans.joinToString("") { it.text }
            is ListBlock -> block.items.joinToString("\n") { item -> item.spans.joinToString("") { it.text } }
        }
    }

    // ---- what is kept

    @Test fun paragraphs() {
        assertEquals(listOf(p("One."), p("Two.")), html("<p>One.</p><p>Two.</p>").blocks)
        assertEquals(listOf(p("One."), p("Two.")), html("<div>One.</div>\n<div>Two.</div>").blocks)
        assertEquals(listOf(p("One."), p("Two.")), html("One.<p>Two.").blocks)
        assertEquals(listOf(p("Bare text.")), html("  Bare\n   text.  ").blocks)
        assertEquals(emptyList(), html("<p> </p><div></div>\n").blocks)
        assertEquals(emptyList(), html("").blocks)
    }

    @Test fun line_breaks() {
        assertEquals(listOf(p("One\nTwo")), html("<p>One<br>Two</p>").blocks)
        assertEquals(listOf(p("One\nTwo")), html("One <br/> Two").blocks)
        assertEquals(listOf(p("One\nTwo")), html("One<BR />Two<br>").blocks)
        assertEquals(listOf(p("One")), html("<br><br>One").blocks)
        // Two in a row is how many catalogues write a paragraph.
        assertEquals(listOf(p("One"), p("Two")), html("One<br><br>Two").blocks)
        assertEquals(listOf(p("One"), p("Two")), html("One<br> <br/><br>Two").blocks)
    }

    @Test fun bold_and_italic() {
        assertEquals(
            listOf(p(Span("A "), Span("bold", bold = true), Span(" and "), Span("slanted", italic = true), Span(" word."))),
            html("<p>A <b>bold</b> and <i>slanted</i> word.</p>").blocks,
        )
        assertEquals(
            listOf(p(Span("strong", bold = true), Span(" "), Span("emphasis", italic = true))),
            html("<strong>strong</strong> <em>emphasis</em>").blocks,
        )
        assertEquals(
            listOf(p(Span("both", bold = true, italic = true), Span(" bold", bold = true))),
            html("<b><i>both</i> bold</b>").blocks,
        )
        // Left open: it ends with its paragraph's text, and nothing crashes.
        assertEquals(listOf(p(Span("Plain "), Span("bold to the end", bold = true))), html("<p>Plain <b>bold to the end").blocks)
        // Closed without being opened.
        assertEquals(listOf(p("Plain text")), html("Plain</b></i> text</em>").blocks)
        assertEquals(listOf(p(Span("Title", italic = true))), html("<cite>Title</cite>").blocks)
    }

    @Test fun a_heading_is_a_bold_paragraph() {
        assertEquals(listOf(p(Span("About", bold = true)), p("The text.")), html("<h2>About</h2>The text.").blocks)
    }

    @Test fun lists() {
        assertEquals(listOf(ListBlock(listOf(bullet("One"), bullet("Two")))), html("<ul><li>One</li><li>Two</li></ul>").blocks)
        assertEquals(listOf(ListBlock(listOf(number(1, "One"), number(2, "Two")))), html("<ol>\n <li>One\n <li>Two\n</ol>").blocks)
        assertEquals(
            listOf(p("Before"), ListBlock(listOf(bullet("One"))), p("After")),
            html("Before<ul><li>One</li></ul>After").blocks,
        )
        assertEquals(
            listOf(ListBlock(listOf(ListItem(listOf(Span("A "), Span("bold", bold = true), Span(" item")), 0, Marker.Bullet)))),
            html("<ul><li>A <b>bold</b> item</li></ul>").blocks,
        )
        // Empty items say nothing and are left out; numbers count what is shown.
        assertEquals(listOf(ListBlock(listOf(number(1, "One"), number(2, "Two")))), html("<ol><li>One</li><li> </li><li>Two</li></ol>").blocks)
        // Paragraphs inside an item become its lines.
        assertEquals(listOf(ListBlock(listOf(bullet("One\nMore")))), html("<ul><li><p>One</p><p>More</p></li></ul>").blocks)
        // An item with no list around it.
        assertEquals(listOf(ListBlock(listOf(bullet("Stray")))), html("<li>Stray</li>").blocks)
        // Never closed.
        assertEquals(listOf(ListBlock(listOf(bullet("One"), bullet("Two")))), html("<ul><li>One<li>Two").blocks)
    }

    @Test fun a_list_inside_a_list_is_indented() {
        assertEquals(
            listOf(ListBlock(listOf(bullet("Fruit"), number(1, "Apple", level = 1), number(2, "Pear", level = 1), bullet("Bread")))),
            html("<ul><li>Fruit<ol><li>Apple</li><li>Pear</li></ol></li><li>Bread</li></ul>").blocks,
        )
    }

    @Test fun links_become_their_text() {
        assertEquals(listOf(p("Read more at the publisher.")), html("""Read <a href="https://example.com/x?y=1">more at the publisher</a>.""").blocks)
        val hostile = html("""<a href="javascript:alert(1)">Click</a> <a href="data:text/html,<script>alert(2)</script>">here</a>""")
        assertEquals(listOf(p("Click here")), hostile.blocks)
    }

    @Test fun entities() {
        assertEquals(listOf(p("Tom & Jerry <3 \"quotes\" 'single'")), html("Tom &amp; Jerry &lt;3 &quot;quotes&quot; &apos;single&apos;").blocks)
        assertEquals(listOf(p("café — “so” … ©")), html("caf&eacute; &mdash; &ldquo;so&rdquo; &hellip; &copy;").blocks)
        assertEquals(listOf(p("A B")), html("A&nbsp;B").blocks)
        assertEquals(listOf(p("é é 😀")), html("&#233; &#xE9; &#x1F600;").blocks)
        // Unknown or unfinished: shown as written.
        assertEquals(listOf(p("AT&T &unknown; &#; &#xZZ; & alone &amp")), html("AT&T &unknown; &#; &#xZZ; & alone &amp").blocks)
        // An entity never becomes markup.
        assertEquals(listOf(p("<script>alert(1)</script>")), html("&lt;script&gt;alert(1)&lt;/script&gt;").blocks)
        assertEquals(listOf(p("<b>not bold</b>")), html("&#60;b&#62;not bold&#60;/b&#62;").blocks)
        // Control and direction-override characters are not text.
        assertEquals(listOf(p("ab cd")), html("a&#0;b&#9;c&#x202E;d&#x7;").blocks)
        assertEquals(listOf(p("big")), html("big&#x110000;&#xD800;").blocks)
        assertEquals(listOf(p("big&#99999999999;")), html("big&#99999999999;").blocks)
    }

    // ---- what is dropped

    @Test fun scripts_and_styles_are_dropped_with_their_content() {
        assertEquals(listOf(p("Before after.")), html("Before <script>alert('x'); if (a<b) document.write(\"</p>\")</script>after.").blocks)
        assertEquals(listOf(p("Before after.")), html("Before <SCRIPT type='text/javascript'>alert(1)</ScRiPt >after.").blocks)
        assertEquals(listOf(p("Before after.")), html("Before <style>p { color: red } </style>after.").blocks)
        assertEquals(listOf(p("Before")), html("Before <script>never closed <b>bold</b>").blocks)
        assertEquals(listOf(p("Before after.")), html("Before <noscript><p>hidden</p></noscript>after.").blocks)
    }

    @Test fun images_tables_and_frames_are_dropped() {
        assertEquals(listOf(p("Before after.")), html("""Before <img src="x.png" alt="ALT TEXT" onerror="alert(1)">after.""").blocks)
        assertEquals(listOf(p("Before after.")), html("""Before<svg><text>drawn</text></svg>after.""").blocks)
        assertEquals(listOf(p("Before"), p("after.")), html("Before<table><tr><td>cell <b>bold</b></td></tr><tr><td><table><tr><td>inner</td></tr></table></td></tr></table>after.").blocks)
        assertEquals(listOf(p("Before after.")), html("""Before <iframe src="https://evil.example">fallback</iframe>after.""").blocks)
        assertEquals(listOf(p("Before after.")), html("""Before <svg onload="alert(1)"><text>drawn</text><script>alert(2)</script></svg>after.""").blocks)
        assertEquals(listOf(p("Before after.")), html("""Before <object data="x"><embed src="y">inside</object>after.""").blocks)
        assertEquals(listOf(p("Before after.")), html("""Before <form action="/x"><input value="typed"><button>Press</button><select><option>o</option></select><textarea>t</textarea></form>after.""").blocks)
        assertEquals(listOf(p("Before after.")), html("Before <!-- a comment <b>with</b> markup -->after.").blocks)
        assertEquals(listOf(p("Before")), html("Before <!-- never closed <b>bold</b>").blocks)
        assertEquals(listOf(p("Text")), html("<!DOCTYPE html><?xml version='1.0'?><html><head><title>T</title><meta charset='x'></head><body>Text</body></html>").blocks)
    }

    @Test fun attributes_never_reach_the_output() {
        val hostile = html(
            """<p onclick="alert('p')" style="position:fixed">One <b onmouseover='steal()' class=x>bold</b>""" +
                """ <span title="a > b" data-x='"'>span</span> <font color=red size=7>font</font></p>""",
        )
        assertEquals(listOf(p(Span("One "), Span("bold", bold = true), Span(" span font"))), hostile.blocks)
    }

    @Test fun broken_markup_is_dropped_not_shown() {
        // A tag that never ends takes the rest with it: showing half a tag would show its attributes.
        assertEquals(listOf(p("Before")), html("""Before <img src="x" onerror="alert(1)""").blocks)
        assertEquals(listOf(p("Before")), html("Before <a href='").blocks)
        // A "<" that starts no tag is a character.
        assertEquals(listOf(p("1 < 2 and 3 > 2, a<5")), html("1 < 2 and 3 > 2, a<5").blocks)
        // End tags with nothing open: a paragraph edge at most.
        assertEquals(listOf(p("Text"), p("more")), html("Text</p></div></ul></li> more").blocks)
        assertEquals(listOf(p("Text more")), html("Text</b></span></a> more").blocks)
        assertEquals(listOf(p("One"), p("Two")), html("<p>One<p>Two</div></span>").blocks)
        assertEquals(listOf(p("a<>b <")), html("a<>b <").blocks)
    }

    @Test fun nothing_hostile_survives_as_text() {
        val inputs = listOf(
            """<script>alert(1)</script>""",
            """<img src=x onerror=alert(1)>""",
            """<a href="javascript:alert(1)">x</a>""",
            """<svg/onload=alert(1)>""",
            """<iframe srcdoc="<script>alert(1)</script>"></iframe>""",
            """<p style="background:url(javascript:alert(1))">x</p>""",
            """<b onfocus=alert(1) autofocus tabindex=1>x</b>""",
            """<math><mtext><script>alert(1)</script></mtext></math>""",
            """<style>@import 'javascript:alert(1)';</style>""",
            """<body onload=alert(1)>x</body>""",
            """<input onfocus=alert(1) autofocus>""",
        )
        inputs.forEach { input ->
            val text = html(input).plain()
            listOf("alert", "javascript", "onerror", "onload", "onfocus", "srcdoc", "<", ">").forEach { bad ->
                assertFalse(bad in text, "$bad survived in \"$text\" from $input")
            }
        }
    }

    @Test fun markup_split_to_dodge_a_filter_ends_up_as_inert_text() {
        // The model can only carry characters; whatever is left of the trick is shown, not run.
        assertEquals(listOf(p("ipt>alert(1)ipt>")), html("<scr<script>ipt>alert(1)</scr</script>ipt>").blocks)
    }

    // ---- bounds

    @Test fun deep_nesting_is_handled_without_recursion() {
        val deep = 10_000
        assertEquals(listOf(p(Span("deep", bold = true))), html("<b>".repeat(deep) + "deep" + "</b>".repeat(deep)).blocks)
        assertEquals(listOf(p("deep")), html("<div>".repeat(deep) + "deep" + "</div>".repeat(deep)).blocks)
        assertEquals(emptyList(), html("<table>".repeat(deep) + "deep" + "</table>".repeat(deep)).blocks)
    }

    @Test fun list_indent_stops_at_the_limit() {
        val depth = 100
        val nested = "<ul><li>level".repeat(depth) + "</li></ul>".repeat(depth) + "<p>after</p>"
        val blocks = html(nested).blocks
        val list = blocks.first() as ListBlock
        assertEquals(depth, list.items.size)
        assertEquals(CatalogueRichText.MAX_LIST_LEVEL, list.items.maxOf { it.level })
        assertEquals((0 until depth).map { minOf(it, CatalogueRichText.MAX_LIST_LEVEL) }, list.items.map { it.level })
        // Every list that was opened was closed again, so what follows is a paragraph.
        assertEquals(p("after"), blocks.last())
    }

    @Test fun input_past_the_limit_is_cut_off() {
        val long = "word ".repeat(CatalogueRichText.MAX_INPUT_CHARS) // five times the limit
        val text = html(long).plain()
        assertTrue(text.length <= CatalogueRichText.MAX_INPUT_CHARS)
        assertTrue(text.length > CatalogueRichText.MAX_INPUT_CHARS - 10)
        // The cut can land inside a tag; that tag is dropped.
        val cutInTag = "a".repeat(CatalogueRichText.MAX_INPUT_CHARS - 5) + """<img src="x" onerror="alert(1)">tail"""
        assertEquals("a".repeat(CatalogueRichText.MAX_INPUT_CHARS - 5), html(cutInTag).plain())
    }

    // ---- the three formats

    @Test fun plain_text_is_never_read_as_markup() {
        assertEquals(listOf(p("One <b>not bold</b> &amp; <script>alert(1)</script>")), sanitizeCatalogueDescription("One <b>not bold</b> &amp; <script>alert(1)</script>", Text).blocks)
        assertEquals(listOf(p("One\nTwo"), p("Three")), sanitizeCatalogueDescription("One\r\nTwo\n\n\n  Three  \n", Text).blocks)
        assertEquals(emptyList(), sanitizeCatalogueDescription(" \n ", Text).blocks)
        assertEquals(listOf(p("ab")), sanitizeCatalogueDescription("a\u0000‮b", Text).blocks)
    }

    @Test fun xhtml_with_prefixes_and_cdata() {
        val xhtml = """<div xmlns="http://www.w3.org/1999/xhtml"><xhtml:p xmlns:xhtml="http://www.w3.org/1999/xhtml">A <xhtml:em>fine</xhtml:em> book.</xhtml:p><p><![CDATA[1 < 2 & <b>not bold</b>]]></p><br/><svg:svg xmlns:svg="http://www.w3.org/2000/svg"><svg:script>alert(1)</svg:script></svg:svg></div>"""
        assertEquals(
            listOf(p(Span("A "), Span("fine", italic = true), Span(" book.")), p("1 < 2 & <b>not bold</b>")),
            sanitizeCatalogueDescription(xhtml, Xhtml).blocks,
        )
    }

    @Test fun a_real_description() {
        val blocks = html(
            """<p><b>Winner of the Hugo Award.</b> A <i>sweeping</i> tale.</p>
               <p>Includes:</p>
               <ul><li>A new foreword</li><li>Maps &amp; notes</li></ul>
               <p>See <a href="https://publisher.example/book">the publisher's page</a>.<br>© 2004</p>""",
        ).blocks
        assertEquals(
            listOf(
                p(Span("Winner of the Hugo Award.", bold = true), Span(" A "), Span("sweeping", italic = true), Span(" tale.")),
                p("Includes:"),
                ListBlock(listOf(bullet("A new foreword"), bullet("Maps & notes"))),
                p("See the publisher's page.\n© 2004"),
            ),
            blocks,
        )
    }
}
