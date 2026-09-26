package com.retro99.analytics.implementation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DiagnosticPayloadSanitizerAndroidTest {

    @Test
    fun preservesOriginalAndroidStackFramesAndIsIdempotent() {
        val original = IllegalStateException("private content")
        val originalFrames = original.stackTrace

        val sanitized = sanitizeDiagnosticThrowable(original)

        assertEquals(originalFrames.toList(), sanitized.stackTrace.toList())
        assertEquals(sanitized, sanitizeDiagnosticThrowable(sanitized))
        assertFalse(sanitized.stackTraceToString().contains("private content"))
    }
}
