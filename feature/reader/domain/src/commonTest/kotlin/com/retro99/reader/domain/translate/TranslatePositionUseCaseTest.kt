package com.retro99.reader.domain.translate

import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.epub.api.EpubChapterText
import com.retro99.epub.api.EpubTextReader
import com.retro99.epub.api.ReadaloudTiming
import com.retro99.epub.api.ReadaloudTimingReader
import com.retro99.reader.domain.fakes.FakePositionDatabase
import com.retro99.reader.domain.usecase.TranslatePositionUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class TranslatePositionUseCaseTest {

    private val textReader = CountingTextReader(
        mapOf(
            "/lib.epub" to book("a", listOf(4, 4, 4)),
            "/st.epub" to book("b", listOf(3, 3, 3, 3)),
        ),
    )
    private val useCase = TranslatePositionUseCase(
        fileLocator = FakeFileLocator(
            mapOf(
                "lib" to CopyFile("/lib.epub", isReadaloud = false, contentHash = null),
                "st" to CopyFile("/st.epub", isReadaloud = false, contentHash = null),
            ),
        ),
        contentCache = CopyContentCache(textReader, NoTiming),
        translationCache = TranslationCache(),
        audiobookDurations = AudiobookDurations { _ -> null },
        positionDatabase = FakePositionDatabase(),
    )
    private val library = linkedCopy(CopySource.Library, "lib")
    private val storyteller = linkedCopy(CopySource.Storyteller, "st")

    @Test
    fun `the same source reading and target return the cached result`() = runTest {
        // Given
        val position = textPosition(book("a", listOf(4, 4, 4)), "a-s5", bookUuid = "lib")
            .copy(observedAt = "2026-10-01T10:00:00Z")

        // When
        val first = useCase(library, position, storyteller)
        val second = useCase(library, position, storyteller)

        // Then
        assertSame(first, second)
        assertEquals(TranslationConfidence.High, first?.confidence)
    }

    @Test
    fun `a changed observation time misses the cache`() = runTest {
        // Given
        val position = textPosition(book("a", listOf(4, 4, 4)), "a-s5", bookUuid = "lib")
            .copy(observedAt = "2026-10-01T10:00:00Z")

        // When
        val first = useCase(library, position, storyteller)
        val later = position.copy(observedAt = "2026-10-01T11:00:00Z")
        val second = useCase(library, later, storyteller)

        // Then
        assertNotSame(first, second)
        assertEquals(first, second)
    }

    @Test
    fun `chapter text is read once per file`() = runTest {
        // Given
        val position = textPosition(book("a", listOf(4, 4, 4)), "a-s5", bookUuid = "lib")

        // When
        useCase(library, position.copy(observedAt = "1"), storyteller)
        useCase(library, position.copy(observedAt = "2"), storyteller)

        // Then
        assertEquals(mapOf("/lib.epub" to 1, "/st.epub" to 1), textReader.reads)
    }

    private class FakeFileLocator(private val files: Map<String, CopyFile>) : CopyFileLocator {
        override suspend fun locate(copy: LinkedCopy): CopyFile? = files[copy.uuid]
    }

    private class CountingTextReader(
        private val books: Map<String, List<EpubChapterText>>,
    ) : EpubTextReader {
        val reads = mutableMapOf<String, Int>()

        override suspend fun readChapters(filePath: String): AppResult<List<EpubChapterText>> {
            reads[filePath] = (reads[filePath] ?: 0) + 1
            return Ok(books.getValue(filePath))
        }
    }

    private object NoTiming : ReadaloudTimingReader {
        override suspend fun readTiming(filePath: String): AppResult<ReadaloudTiming> =
            error("no read-aloud in this test")
    }
}

fun linkedCopy(
    source: CopySource,
    id: String,
    hasEbook: Boolean = true,
    hasAudiobook: Boolean = false,
    hasReadaloud: Boolean = false,
    serverId: String = "${source.prefix}-server",
) = LinkedCopy(
    key = CopyKey(source, id),
    serverId = serverId,
    uuid = id,
    title = "Book",
    home = when (source) {
        CopySource.Library -> BookHome.ThisDevice
        CopySource.Storyteller -> BookHome.Storyteller
        CopySource.Audiobookshelf -> BookHome.Audiobookshelf
    },
    hasEbook = hasEbook,
    hasAudiobook = hasAudiobook,
    hasReadaloud = hasReadaloud,
    isDownloaded = true,
)
