package com.retro99.reader.ui.navigator

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SentenceDoubleTapRecognizerTest {

    private var currentTimeMs = 0L
    private lateinit var classUnderTest: SentenceDoubleTapRecognizer

    @BeforeTest
    fun setup() {
        currentTimeMs = 1_000L
        classUnderTest = SentenceDoubleTapRecognizer { currentTimeMs }
    }

    @Test
    fun `registerTap recognizes two taps on the same sentence within timeout`() {
        // Given
        classUnderTest.registerTap(fragmentId = "sentence-1", timeoutMs = 400)
        currentTimeMs += 300L

        // When
        val result = classUnderTest.registerTap(fragmentId = "sentence-1", timeoutMs = 400)

        // Then
        assertTrue(result)
    }

    @Test
    fun `registerTap rejects taps on different sentences`() {
        // Given
        classUnderTest.registerTap(fragmentId = "sentence-1", timeoutMs = 400)
        currentTimeMs += 300L

        // When
        val result = classUnderTest.registerTap(fragmentId = "sentence-2", timeoutMs = 400)

        // Then
        assertFalse(result)
    }

    @Test
    fun `registerTap treats a different sentence as the start of a new gesture`() {
        // Given
        classUnderTest.registerTap(fragmentId = "sentence-1", timeoutMs = 400)
        currentTimeMs += 100L
        classUnderTest.registerTap(fragmentId = "sentence-2", timeoutMs = 400)
        currentTimeMs += 100L

        // When
        val result = classUnderTest.registerTap(fragmentId = "sentence-2", timeoutMs = 400)

        // Then
        assertTrue(result)
    }

    @Test
    fun `registerTap rejects taps outside timeout`() {
        // Given
        classUnderTest.registerTap(fragmentId = "sentence-1", timeoutMs = 400)
        currentTimeMs += 400L

        // When
        val result = classUnderTest.registerTap(fragmentId = "sentence-1", timeoutMs = 400)

        // Then
        assertFalse(result)
    }
}
