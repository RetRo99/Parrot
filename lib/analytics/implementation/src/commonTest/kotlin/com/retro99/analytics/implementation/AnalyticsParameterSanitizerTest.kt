package com.retro99.analytics.implementation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AnalyticsParameterSanitizerTest {

    @Test
    fun removesPrivateAndUnregisteredDimensions() {
        val sanitized = sanitizeAnalyticsParameters(
            mapOf(
                "book_uuid" to "private-book-id",
                "profile_name" to "private-profile",
                "profile_id" to "private-profile-id",
                "error_message" to "private exception text",
                "server_url_hash" to "server-identifier",
                "font_name" to "private-font-filename",
                "query" to "private search text",
                "preview_text" to "private spoken text",
                "endpoint" to "/user/private-id/books",
            ),
        )

        assertEquals(emptyMap(), sanitized)
    }

    @Test
    fun retainsRegisteredBoundedDimensionsAndMetrics() {
        val sanitized = sanitizeAnalyticsParameters(
            mapOf(
                "screen" to "books_library",
                "entry_point" to "continue_reading",
                "book_type" to "ebook",
                "is_success" to true,
                "progress_percent" to 42,
                "status_code" to 404,
                "duration_ms" to 1_200L,
                "rate" to 1.25f,
            ),
        )

        assertEquals(
            mapOf(
                "screen" to "books_library",
                "entry_point" to "continue_reading",
                "book_type" to "ebook",
                "is_success" to true,
                "progress_percent" to 42,
                "status_code" to 404,
                "duration_ms" to 1_200L,
                "rate" to 1.25f,
            ),
            sanitized,
        )
    }

    @Test
    fun rejectsFreeFormValuesAndUnexpectedTypesEvenForRegisteredKeys() {
        val sanitized = sanitizeAnalyticsParameters(
            mapOf(
                "source" to "https://example.invalid/user/private",
                "error_type" to "private failure details with spaces",
                "screen" to "x".repeat(65),
                "is_success" to "true",
                "progress_percent" to 1000,
                "status_code" to 600,
                "duration_ms" to -1L,
                "rate" to Float.NaN,
            ),
        )

        assertEquals(emptyMap(), sanitized)
        assertFalse("profile_name" in sanitized)
    }
}

class DiagnosticPayloadSanitizerTest {

    @Test
    fun removesThrowableAndCauseMessagesButRetainsTypesAndCauseStructure() {
        val cause = IllegalStateException("private cause path /storage/emulated/0/secret.epub")
        val original = IllegalArgumentException("private server response: user@example.invalid", cause)

        val sanitized = sanitizeDiagnosticThrowable(original)

        val root = assertIs<SanitizedDiagnosticException>(sanitized)
        assertEquals("IllegalArgumentException", root.sourceType)
        assertFalse(sanitized.stackTraceToString().contains("user@example.invalid"))
        assertFalse(sanitized.stackTraceToString().contains("secret.epub"))
        val sanitizedCause = assertIs<SanitizedDiagnosticException>(root.cause)
        assertEquals("IllegalStateException", sanitizedCause.sourceType)
    }

    @Test
    fun diagnosticMessageNeverForwardsCallerText() {
        val sanitized = sanitizeDiagnosticMessage("private profile and /private/file.epub")

        assertEquals("Handled exception; free-form context omitted", sanitized)
        assertFalse(sanitized.orEmpty().contains("private profile"))
    }
}
