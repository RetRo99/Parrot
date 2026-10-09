package com.retro99.opds.implementation.mediatype

import com.retro99.opds.api.OpdsContentType
import com.retro99.opds.api.model.OpdsRejection
import com.retro99.opds.implementation.fixtures.Fixtures
import com.retro99.opds.implementation.fixtures.readFixtureText
import com.retro99.opds.implementation.detect.OpdsDocumentDetector
import com.retro99.opds.implementation.mediatype.SeparatedMediaTypeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * Plan §4 (Phase 1 test-first order 2): media-type parsing (parameters,
 * order, case) and document detection; HTML/error bodies are rejected; DOCTYPE
 * is rejected at detection time, per the Phase 0 record.
 */
class OpdsMediaTypeParserTest {

    private val parser = SeparatedMediaTypeParser()

    @Test
    fun parameters_order_and_case() {
        val mt = parser.parse("APPLICATION/ATOM+XML; PROFILE=opds-catalog;kind=acquisition; charset=\"utf-8\"")!!
        assertEquals("application", mt.mainType)
        assertEquals("atom+xml", mt.subType)
        assertEquals("opds-catalog", mt.parameter("profile"))
        assertEquals("acquisition", mt.parameter("kind"))
        assertEquals("utf-8", mt.parameter("charset"))
        // Parameter order must not matter.
        assertEquals(
            mt.parameter("kind"),
            parser.parse("application/atom+xml;kind=acquisition;profile=opds-catalog")!!.parameter("kind"),
        )
    }

    @Test
    fun quoted_parameter_values_and_missing_handling() {
        val mt = parser.parse("application/atom+xml;profile=opds-catalog")!!
        assertEquals("application", mt.mainType)
        assertEquals("atom+xml", mt.subType)
        assertNull(mt.parameter("kind"))
    }

    @Test
    fun garbage_header_is_not_a_media_type() {
        assertNull(parser.parse(""))
        assertNull(parser.parse("html"))
        assertNull(parser.parse("a/b/c/d"))
        assertNull(parser.parse("text/ ;charset=utf8"))
        // Double semicolons (empty parameter) are real-world tolerable noise;
        // the type itself is unambiguous, so it parses.
        val tolerated = parser.parse("application/atom+xml;;kind=nav")!!
        assertEquals("atom+xml", tolerated.subType)
        assertEquals("nav", tolerated.parameter("kind"))
    }

    @Test
    fun exact_string_comparison_is_not_used() {
        // Same type written differently must compare equal (plan §2.2).
        val a = parser.parse("application/opds+json")!!
        val b = parser.parse("APPLICATION/OPDS+JSON")!!
        assertEquals(a.mediaRange, b.mediaRange)
        assertTrue(a.mainType == b.mainType && a.subType == b.subType)
    }
}

class OpdsDocumentDetectorTest {

    private val detector: OpdsDocumentDetector = OpdsDocumentDetector()

    @Test
    fun declared_media_types_identify_documents() {
        // Declared OPDS media types classify without deep probing.
        assertEquals(
            OpdsContentType.FEED,
            detector.detect(mediaTypeHeader = "application/opds+json", body = "{}".encodeToByteArray()),
        )
        assertEquals(
            OpdsContentType.FEED,
            detector.detect(
                mediaTypeHeader = "application/atom+xml;profile=opds-catalog;kind=navigation",
                body = "<feed/>".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun gutenberg_s_plain_atom_response_type_is_an_accepted_compatibility_case() {
        // Phase 0 live check: Gutenberg's search endpoint answers plain
        // `application/atom+xml` (no profile). Structure must prove OPDS.
        val body = readFixtureText(Fixtures.OPDS1_LISTING).encodeToByteArray()
        assertEquals(
            OpdsContentType.FEED,
            detector.detect(mediaTypeHeader = "application/atom+xml", body = body),
        )
    }

    @Test
    fun html_error_page_is_not_a_catalogue() {
        val body = readFixtureText(Fixtures.ERROR_HTML).encodeToByteArray()
        val outcome = detector.detect(mediaTypeHeader = "text/html; charset=utf-8", body = body)
        assertTrue(outcome is OpdsContentType.NotACatalogue, "got $outcome")
    }

    @Test
    fun generic_xml_body_is_classified_by_structure() {
        val body = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><id>u</id><title>t</title></feed>".encodeToByteArray()
        assertEquals(OpdsContentType.FEED, detector.detect(mediaTypeHeader = "application/xml", body = body))
    }

    @Test
    fun rss_is_rejected() {
        val body = "<rss version=\"2.0\"><channel/></rss>".encodeToByteArray()
        val outcome = detector.detect(mediaTypeHeader = "application/xml", body = body)
        assertTrue(outcome is OpdsContentType.NotACatalogue, "got $outcome")
    }

    @Test
    fun doctype_is_rejected_at_detection_before_any_content_is_read() {
        // Phase 0 correction: xmlutil accepts internal-DTD documents and expands
        // entities — so the DOCUMENT DETECTOR must refuse DOCTYPE-bearing bodies
        // before any entity content is produced.
        val body = readFixtureText(Fixtures.DTD_BASELINE).encodeToByteArray()
        val outcome = detector.detect(mediaTypeHeader = "application/xml", body = body)
        assertTrue(
            outcome is OpdsContentType.Rejected &&
                outcome.rejection is OpdsRejection.DocumentTypeDeclarationRejected,
            "got $outcome",
        )
    }
}
