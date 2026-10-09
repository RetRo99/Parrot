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
        val bookTitle: String = "",
    ) : HomeDestination

    /** "Same book?": review books that may be the same across servers. */
    @Serializable
    data object LinkReview : HomeDestination

    @Serializable
    data class NotesHighlights(val bookKey: String? = null) : HomeDestination

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

    /** "Get books": the user's book catalogues and the ones to start with. */
    @Serializable
    data object CatalogueSources : HomeDestination

    /**
     * One page of a catalogue. [targetRef] is a short in-memory reference to the page, or null
     * for the catalogue's first page. Never the page's address: it can hold a key (plan §10.6).
     */
    @Serializable
    data class CatalogueBrowse(
        val sourceId: String,
        val targetRef: String? = null,
    ) : HomeDestination {
        init {
            requireCatalogueRouteArgument(sourceId)
            targetRef?.let(::requireCatalogueRouteArgument)
        }
    }

    /** A book's page in a catalogue. [publicationRef] is a short in-memory reference, as above. */
    @Serializable
    data class CataloguePublication(
        val sourceId: String,
        val publicationRef: String,
    ) : HomeDestination {
        init {
            requireCatalogueRouteArgument(sourceId)
            requireCatalogueRouteArgument(publicationRef)
        }
    }

    /** Downloads from catalogues: running, waiting, failed and just finished. */
    @Serializable
    data object CatalogueDownloads : HomeDestination

    /** Only the catalogue id is restored, never its address or account details. */
    @Serializable
    data class CatalogueSettings(val sourceId: String, val editAccount: Boolean = false) : HomeDestination {
        init { requireCatalogueRouteArgument(sourceId) }
    }

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
            this is LinkReview ||
            this is NotesHighlights ||
            this is CatalogueSources ||
            this is CatalogueBrowse ||
            this is CataloguePublication ||
            this is CatalogueDownloads
            || this is CatalogueSettings
}

/**
 * A catalogue route argument is an id or a reference: letters, digits, "-" and "_", at most 64.
 * That shape cannot hold an address or account details, so neither can end up in saved
 * navigation state. The refused value is not repeated in the message.
 */
private fun requireCatalogueRouteArgument(value: String) {
    require(value.length in 1..MAX_CATALOGUE_ROUTE_ARGUMENT_LENGTH && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) {
        "A catalogue route takes an id or a reference, not an address"
    }
}

private const val MAX_CATALOGUE_ROUTE_ARGUMENT_LENGTH = 64

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
