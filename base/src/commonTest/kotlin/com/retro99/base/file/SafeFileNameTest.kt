package com.retro99.base.file

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SafeFileNameTest {

    @Test
    fun `keeps uuid and audiobookshelf ids unchanged`() {
        listOf(
            "3f2b8c1e-5a4d-4e8b-9c1a-2b3c4d5e6f70",
            "li_8gch9ve09orgn4fdz8",
        ).forEach { id ->
            assertEquals(id, id.safeFileName())
        }
    }

    @Test
    fun `neutralises path traversal and separators`() {
        // Given
        val ids = listOf("../../shared_prefs/x", "..", ".", "a/b", "a\\b", "/abs", "a\u0000b")

        // Then
        ids.forEach { id ->
            val name = id.safeFileName()
            assertFalse('/' in name, name)
            assertFalse('\\' in name, name)
            assertFalse('.' in name, name)
        }
        assertEquals("______shared_prefs_x", "../../shared_prefs/x".safeFileName())
    }
}
