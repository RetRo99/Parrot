package com.retro99.books.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ContentHashTest {

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals(
            expected = "ba7816bf8f01cfea414140de5dae2223" +
                "b00361a396177a9cb410ff61f20015ad",
            actual = sha256("abc".encodeToByteArray()).toHexString(),
        )
        assertEquals(
            expected = "e3b0c44298fc1c149afbf4c8996fb924" +
                "27ae41e4649b934ca495991b7852b855",
            actual = sha256(ByteArray(0)).toHexString(),
        )
    }

    @Test
    fun sha256SupportsChunkedUpdates() {
        val bytes = ByteArray(1024) { index -> index.toByte() }
        val digest = Sha256Digest()
        digest.update(bytes, 0, 0)
        digest.update(bytes, 0, 17)
        digest.update(bytes, 17, 55)
        digest.update(bytes, 72, bytes.size - 72)

        assertContentEquals(expected = sha256(bytes), actual = digest.digest())
    }
}
