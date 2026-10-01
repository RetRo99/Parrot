package com.retro99.home.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.retro99.books.domain.model.BookType
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Interface for destinations that control bottom navigation bar visibility.
 * Implement this interface and override [showBottomBar] to return `false`
 * for full-screen destinations like readers.
 */
interface BottomBarDestination {
    /**
     * Whether the bottom navigation bar should be visible when this destination is displayed.
     * Default is `true`.
     */
    val showBottomBar: Boolean
        get() = true
}

@Serializable
sealed interface HomeDestination : NavKey, BottomSheetDestination, BottomBarDestination {

    @Serializable
    data object BooksList : HomeDestination

    @Serializable
    data object SeriesList : HomeDestination

//    @Serializable
//    data object AuthorsList : HomeDestination

    @Serializable
    data class BookDetail(
        val serverId: String,
        val bookUuid: String,
    ) : HomeDestination

    /** "Same book as…": pick a book on another server to link this book to. */
    @Serializable
    data class LinkPicker(
        val serverId: String,
        val bookUuid: String,
    ) : HomeDestination

    /** "Reading positions": every linked copy's position, and applying one to others. */
    @Serializable
    data class Positions(
        val serverId: String,
        val bookUuid: String,
    ) : HomeDestination

    /** "Same book?": review books that may be the same across servers. */
    @Serializable
    data object LinkReview : HomeDestination

    @Serializable
    data class SeriesDetail(
        val seriesUuid: String,
        val seriesName: String,
    ) : HomeDestination

//    @Serializable
//    data class AuthorDetail(
//        val authorUuid: String,
//        val authorName: String,
//    ) : HomeDestination

    @Serializable
    data class Reader(
        val serverId: String,
        val bookUuid: String,
        val bookType: BookType,
        val isLastBookOnLaunch: Boolean = false,
        val readerOpenEntryPoint: String? = null,
        val readerOpenCorrelationId: String? = null,
        /** Book detail already asked about a newer linked copy: the reader asks nothing. */
        val linkedResumeResolved: Boolean = false,
        val listenMode: Boolean = false,
    ) : HomeDestination {
        @Transient
        override val showBottomBar: Boolean = false
    }

    @Serializable
    data object Settings : HomeDestination {
        @Transient
        override val showBottomBar: Boolean = false
    }

    @Serializable
    data object AppSettings : HomeDestination

    @Serializable
    data object ServerManagement : HomeDestination

    @Serializable
    data object SyncAndBackup : HomeDestination

    @Serializable
    data object Diagnostics : HomeDestination

    @Serializable
    data object Statistics : HomeDestination

    /**
     * Destinations where the floating continue-reading bubble must stay hidden:
     * settings-style screens (it would cover rows, toggles, and buttons) and the
     * book detail screen (it would cover the progress bar it duplicates).
     */
    val hidesContinueBubble: Boolean
        get() = this is Settings ||
            this is AppSettings ||
            this is ServerManagement ||
            this is SyncAndBackup ||
            this is Diagnostics ||
            this is Statistics ||
            this is BookDetail ||
            this is LinkPicker ||
            this is Positions ||
            this is LinkReview
}

/** Returns whether this destination already restores the requested Reader route. */
internal fun HomeDestination?.isReaderFor(
    serverId: String,
    bookUuid: String,
    bookType: BookType,
): Boolean {
    val reader = this as? HomeDestination.Reader ?: return false
    return reader.serverId == serverId &&
        reader.bookUuid == bookUuid &&
        reader.bookType == bookType
}

/** Returns whether the restored matching Reader owns the pending last-book outcome. */
internal fun HomeDestination?.isLastBookLaunchReaderFor(
    serverId: String,
    bookUuid: String,
    bookType: BookType,
): Boolean {
    val reader = this as? HomeDestination.Reader ?: return false
    return reader.isLastBookOnLaunch && reader.isReaderFor(serverId, bookUuid, bookType)
}
