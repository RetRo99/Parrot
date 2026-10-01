package com.retro99.books.data

/**
 * Result of EPUB metadata extraction from the Swift bridge.
 */
data class EpubMetadataResult(
    val title: String,
    val author: String?,
    val description: String?,
    val coverFilePath: String?,
    val hasMediaOverlays: Boolean,
    val publicationDate: String?,
    /** The publication's `dc:identifier`, whatever kind it is. */
    val identifier: String?,
)

/**
 * Bridge interface for EPUB metadata extraction on iOS.
 * Implemented in Swift and registered at app startup.
 */
interface EpubMetadataBridge {
    /**
     * Extracts metadata from an EPUB file.
     * @param filePath The absolute path to the EPUB file
     * @param callback Called with the result, or null if extraction fails
     */
    fun extractMetadata(filePath: String, callback: (EpubMetadataResult?) -> Unit)
}

/**
 * Registry for the EPUB metadata bridge.
 * Swift code registers its implementation here at app initialization.
 */
object EpubMetadataBridgeRegistry {
    private var bridge: EpubMetadataBridge? = null

    fun register(bridge: EpubMetadataBridge) {
        this.bridge = bridge
    }

    fun getBridge(): EpubMetadataBridge? = bridge

    fun isRegistered(): Boolean = bridge != null
}
