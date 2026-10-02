package com.retro99.base.file

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeFileNameTest {

    @Test
    fun `keeps uuid and audiobookshelf ids unchanged`() {
        listOf(
            "3f2b8c1e-5a4d-4e8b-9c1a-2b3c4d5e6f70",
            "li_8gch9ve09orgn4fdz8",
        ).forEach { id ->
            assertEquals(id, id.safeFileName())
            assertEquals(id, id.encodeFileNameSegment())
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

    @Test
    fun `encoded segment keeps already safe dotted ids verbatim`() {
        assertEquals("book.v1", "book.v1".encodeFileNameSegment())
    }

    @Test
    fun `encoded segment never traverses`() {
        // Given
        val ids = listOf("../../shared_prefs/x", "..", ".", "a/b", "a\\b", "/abs", "a\u0000b", "")

        // Then
        ids.forEach { id ->
            val name = id.encodeFileNameSegment()
            assertFalse('/' in name, name)
            assertFalse('\\' in name, name)
            assertFalse('\u0000' in name, name)
            assertTrue(name.startsWith("%"), name)
        }
        assertEquals("%%002E%002E", "..".encodeFileNameSegment())
    }

    @Test
    fun `encoded segment does not collide for ids safeFileName merges`() {
        // Given
        val ids = listOf(
            "foo/bar", "foo\\bar", "foo.bar", "foo_bar", "foo bar", "foo%bar",
            "%foo", "%25foo", "foö", "fo_", ".", "..", "%", "",
        )

        // When
        val names = ids.map { id -> id.encodeFileNameSegment() }

        // Then
        assertEquals(ids.size, names.toSet().size, names.toString())
    }
}
