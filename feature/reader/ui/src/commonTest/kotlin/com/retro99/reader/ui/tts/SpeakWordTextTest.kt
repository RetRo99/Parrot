package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeakWordTextTest {

    @Test
    fun `lowercases ordinary words but keeps the surface form`() {
        assertEquals("hello", prepareWordForSpeech("Hello"))
        assertEquals("ran", prepareWordForSpeech("ran"))
        assertEquals("running", prepareWordForSpeech("Running"))
    }

    @Test
    fun `trims surrounding punctuation and quotes`() {
        assertEquals("word", prepareWordForSpeech("«word»"))
        assertEquals("word", prepareWordForSpeech("\"word\""))
        assertEquals("word", prepareWordForSpeech("'word'"))
        assertEquals("word", prepareWordForSpeech("word."))
        assertEquals("word", prepareWordForSpeech("  word  "))
        assertEquals("word", prepareWordForSpeech("(word),"))
    }

    @Test
    fun `strips soft hyphens and zero-width characters`() {
        assertEquals("wellknown", prepareWordForSpeech("well\u00ADknown"))
        assertEquals("word", prepareWordForSpeech("\uFEFFword\u200B"))
        assertEquals("word", prepareWordForSpeech("wo\u200Drd"))
    }

    @Test
    fun `keeps internal apostrophes and hyphens`() {
        assertEquals("don't", prepareWordForSpeech("don't"))
        assertEquals("don't", prepareWordForSpeech("don’t"))
        assertEquals("o'clock", prepareWordForSpeech("o'clock"))
        assertEquals("well-known", prepareWordForSpeech("well-known"))
    }

    @Test
    fun `keeps genuine acronyms and lowers the rest`() {
        assertEquals("NATO", prepareWordForSpeech("NATO"))
        assertEquals("HTML", prepareWordForSpeech("html".uppercase()))
        assertEquals("OK", prepareWordForSpeech("OK"))
        assertEquals("unesco", prepareWordForSpeech("UNESCO"))
    }

    @Test
    fun `speech text never carries a trailing full stop`() {
        assertEquals("read", prepareWordForSpeech("read"))
    }

    @Test
    fun `accepts single word forms`() {
        assertTrue(isSpeakableWordForm("don't"))
        assertTrue(isSpeakableWordForm("well-known"))
        assertTrue(isSpeakableWordForm("NATO"))
        assertTrue(isSpeakableWordForm("'quoted'"))
        assertTrue(isSpeakableWordForm("mice"))
    }

    @Test
    fun `rejects digits-only symbols and phrases`() {
        assertFalse(isSpeakableWordForm("1984"))
        assertFalse(isSpeakableWordForm("in the"))
        assertFalse(isSpeakableWordForm("—"))
        assertFalse(isSpeakableWordForm(""))
        assertFalse(isSpeakableWordForm("\u00AD"))
        assertFalse(isSpeakableWordForm("a".repeat(101)))
    }
}
