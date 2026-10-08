package com.retro99.server.api

/**
 * Enough to find one file of one publication again later. It names the catalogue page that
 * lists the publication, never the file's own link: a download link can be signed and expire.
 *
 * [documentUrl] can hold a key in its path, so it stays on this device and out of logs.
 */
data class CatalogueAcquisitionLocator(
    val documentUrl: String,
    val publicationKey: String,
    val representationKey: String,
) {
    override fun toString() = "CatalogueAcquisitionLocator(redacted)"
}

/** Where a downloaded file's bytes go. Throwing from either call stops the download. */
interface CatalogueFileSink {
    /** Once, when the file's response has started: the size the catalogue declared, if any. */
    suspend fun start(declaredLength: Long?) {}

    /** The first [length] bytes of [buffer]. The buffer is reused. */
    suspend fun write(buffer: ByteArray, length: Int)
}

sealed interface CatalogueDownloadOutcome {
    data class Complete(val bytes: Long, val declaredLength: Long?) : CatalogueDownloadOutcome
    data class Failed(val kind: CatalogueDownloadFailure) : CatalogueDownloadOutcome

    /** The sink threw; [cause] is its own exception. */
    class SinkFailed(val cause: Throwable) : CatalogueDownloadOutcome
}

enum class CatalogueDownloadFailure {
    /** The catalogue asks for account details (401 with a sign-in method Parrot supports). */
    SignInNeeded,

    /** The catalogue said no, or no longer lists this file. */
    Refused,

    /** Over the per-file ceiling. */
    TooLarge,

    /** Network trouble, a timeout, or fewer bytes than declared. */
    Connection,
}

/** A catalogue that can hand over book files. Implemented next to [ServerCatalogueRepository]. */
interface CatalogueAcquisitionRepository {
    /**
     * A locator for [choice] of [publication] as listed in [document], or null when that file
     * cannot be downloaded directly or does not belong to this catalogue session.
     */
    fun locate(
        document: CatalogueDocument,
        publication: CataloguePublication,
        choice: CatalogueFileChoice,
    ): CatalogueAcquisitionLocator?

    /**
     * Loads the listing page again, finds the file's current link and streams it to [sink].
     * Nothing is kept in memory and nothing is cached.
     */
    suspend fun download(locator: CatalogueAcquisitionLocator, sink: CatalogueFileSink): CatalogueDownloadOutcome
}

/** The stable key of a publication inside one catalogue. */
val CataloguePublication.publicationKey: String get() = identity.value

/**
 * A key for one downloadable file of this publication that survives the link changing: its
 * media type and its place among the files of that type, in catalogue order. Null for a file
 * that is not a direct download.
 */
fun CataloguePublication.representationKeyOf(choice: CatalogueFileChoice): String? =
    representationKeys().firstOrNull { (candidate, _) -> candidate === choice || candidate == choice }?.second

fun CataloguePublication.choiceForRepresentation(representationKey: String): CatalogueFileChoice? =
    representationKeys().firstOrNull { (_, key) -> key == representationKey }?.first

private fun CataloguePublication.representationKeys(): List<Pair<CatalogueFileChoice, String>> {
    val seen = mutableMapOf<String, Int>()
    return acquisitionChoices
        .filter { choice -> choice.action is CatalogueAcquisitionAction.Download }
        .map { choice ->
            val type = choice.link.mediaType?.let { "${it.type}/${it.subtype}".lowercase() } ?: "unknown"
            val ordinal = (seen[type] ?: 0) + 1
            seen[type] = ordinal
            choice to "$type#$ordinal"
        }
}
