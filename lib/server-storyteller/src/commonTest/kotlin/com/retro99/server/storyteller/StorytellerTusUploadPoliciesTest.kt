package com.retro99.server.storyteller

import com.retro99.cloud.implementation.transfer.TusAccessTokenProvider
import com.retro99.cloud.implementation.transfer.TusRequestType
import com.retro99.cloud.implementation.transfer.TusUploadMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StorytellerTusUploadPoliciesTest {
    @Test
    fun metadataUsesStorytellerUploadVocabularyAndMediaType() {
        val metadata = StorytellerTusMetadataEncoder().encode(
            TusUploadMetadata(
                targetPath = "ignored-by-storyteller",
                bookUuid = "book-123",
                fileName = "the-book.epub",
                mediaType = "ebook",
                sizeBytes = 42,
                contentHash = "sha256-value",
                totalFiles = 3,
            ),
        )

        assertEquals(
            linkedMapOf(
                "bookUuid" to "book-123",
                "filename" to "the-book.epub",
                "filetype" to "application/epub+zip",
                "totalFiles" to "3",
            ),
            metadata,
        )
    }

    @Test
    fun storytellerRequiresBearerForHeadAndDoesNotAddApikey() {
        val policy = StorytellerTusRequestHeaderPolicy(TusAccessTokenProvider { "storyteller-token" })

        val headers = policy.headersFor(TusRequestType.Head)

        assertEquals("Bearer storyteller-token", headers["Authorization"])
        assertFalse("apikey" in headers)
    }
}
