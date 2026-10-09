package com.retro99.base.server

/**
 * Constant for the local server ID.
 * Used to identify the local/imported books server.
 */
const val LOCAL_SERVER_ID = "local"
const val PARROT_CLOUD_SERVER_ID = "parrot-cloud"

/**
 * Represents the type of media server.
 * Each type has different API endpoints, authentication methods, and capabilities.
 */
enum class ServerType(
    val identifier: String,
    val displayName: String,
) {
    Storyteller(
        identifier = "storyteller",
        displayName = "Storyteller",
    ),
    Audiobookshelf(
        identifier = "audiobookshelf",
        displayName = "Audiobookshelf",
    ),
    ParrotCloud(
        identifier = PARROT_CLOUD_SERVER_ID,
        displayName = "Parrot Cloud",
    ),
    Opds(
        identifier = "opds",
        displayName = "OPDS",
    ),
    Local(
        identifier = LOCAL_SERVER_ID,
        displayName = "Local",
    );

    companion object {
        fun fromIdentifier(identifier: String): ServerType? {
            return entries.find { it.identifier == identifier }
        }
    }
}
