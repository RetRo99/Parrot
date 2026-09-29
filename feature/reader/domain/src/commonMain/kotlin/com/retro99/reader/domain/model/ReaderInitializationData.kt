package com.retro99.reader.domain.model

import com.retro99.books.domain.model.BookType

/**
 * Contains all data needed to initialize the reader.
 *
 * This is returned by [InitializeReaderUseCase] after:
 * - Fetching the book metadata
 * - Preparing/downloading the ebook file
 * - Loading reader settings
 * - Detecting reading progress conflicts
 *
 * @param serverId The ID of the server this book belongs to
 * @param bookUuid The unique identifier of the book
 * @param bookTitle The title of the book
 * @param bookAuthor The author or authors of the book
 * @param bookCoverUrl The URL of the book cover image
 * @param localEbookPath The local file path where the ebook is ready to be opened
 * @param bookType The type of book (EBOOK, AUDIOBOOK, or READALOUD)
 * @param initialSettings The initial reader settings to apply
 * @param progressResult The reading progress result, which may contain a conflict
 */
data class ReaderInitializationData(
    val serverId: String,
    val bookUuid: String,
    val bookTitle: String,
    val bookAuthor: String = "",
    val bookCoverUrl: String?,
    val localEbookPath: String,
    val bookType: BookType,
    val initialSettings: ReaderSettingsDomainModel,
    val progressResult: ReadingProgressResult,
)
