package com.retro99.reader.data.model

import com.retro99.database.api.reader.ReaderSettingsEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderSettingsJsonCodecTest {

    @Test
    fun `decode returns defaults when nothing is stored`() {
        assertEquals(ReaderSettingsLocalModel(), ReaderSettingsJsonCodec.decode(emptyList()))
    }

    @Test
    fun `decode merges stored settings over defaults`() {
        val stored = listOf(
            entity(key = "font_size", value = "2.5"),
            entity(key = "theme", value = "\"DARK\""),
        )

        val decoded = ReaderSettingsJsonCodec.decode(stored)

        assertEquals(2.5, decoded.fontSize)
        assertEquals("DARK", decoded.theme)
        assertEquals(ReaderSettingsLocalModel().fontFamily, decoded.fontFamily)
    }

    @Test
    fun `decode ignores unknown keys and invalid values`() {
        val stored = listOf(
            entity(key = "not_a_setting", value = "\"ignored\""),
            entity(key = "font_size", value = "not json"),
            entity(key = "line_height", value = "2.0"),
        )

        val decoded = ReaderSettingsJsonCodec.decode(stored)

        assertEquals(expected = 2.0f, actual = decoded.lineHeight, absoluteTolerance = 0.0001f)
        assertEquals(ReaderSettingsLocalModel().fontSize, decoded.fontSize)
    }

    @Test
    fun `encodeDiff returns nothing when settings match stored`() {
        val settings = ReaderSettingsLocalModel(fontSize = 1.5, theme = "DARK")
        val stored = ReaderSettingsJsonCodec.encodeAll(settings)

        assertTrue(ReaderSettingsJsonCodec.encodeDiff(settings = settings, stored = stored).isEmpty())
    }

    @Test
    fun `encodeDiff returns only changed settings and preserves revision`() {
        val stored = ReaderSettingsJsonCodec.encodeAll(ReaderSettingsLocalModel())
            .map { entry ->
                if (entry.key == "theme") entry.copy(remoteRevision = 7L) else entry
            }

        val changed = ReaderSettingsJsonCodec.encodeDiff(
            settings = ReaderSettingsLocalModel(theme = "DARK"),
            stored = stored,
        )

        assertEquals(1, changed.size)
        assertEquals("theme", changed.single().key)
        assertEquals("\"DARK\"", changed.single().value)
        assertEquals(7L, changed.single().remoteRevision)
    }

    @Test
    fun `encodeAll covers every serialized setting`() {
        val encoded = ReaderSettingsJsonCodec.encodeAll(ReaderSettingsLocalModel())

        assertTrue(encoded.any { entry -> entry.key == "font_size" })
        assertTrue(encoded.any { entry -> entry.key == "tts_voice_id" })
        assertTrue(encoded.size > 30)
    }

    private fun entity(key: String, value: String): ReaderSettingsEntity {
        return ReaderSettingsEntity(
            key = key,
            value = value,
            remoteRevision = null,
            deletedAt = null,
        )
    }
}
