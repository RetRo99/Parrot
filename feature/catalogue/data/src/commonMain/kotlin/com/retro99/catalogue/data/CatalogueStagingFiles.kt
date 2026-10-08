package com.retro99.catalogue.data

/**
 * Where catalogue downloads sit until they are added to the library. File names are
 * generated here: nothing a catalogue sends (a file name, a title, a header) reaches a path.
 */
interface CatalogueStagingFiles {
    /** A new path under this profile's staging folder, ending in [PART_SUFFIX]. */
    fun newPartPath(profileId: String): String

    /** Creates or empties the file and opens it for appending. */
    suspend fun openForWriting(path: String): StagingWriter

    /** Free space where staging files live, or null when it cannot be told. */
    suspend fun freeSpaceBytes(): Long?

    suspend fun size(path: String): Long

    /** Lower-case hex SHA-256 of the file, read in chunks. */
    suspend fun sha256(path: String): String

    suspend fun rename(from: String, to: String): Boolean

    /** Deletes the file if it is there. Never throws. */
    suspend fun delete(path: String)

    /** Every file in [profileId]'s staging folder, as absolute paths. Empty when it cannot be read. */
    suspend fun list(profileId: String): List<String>

    /**
     * Removes the staging folder of every profile that is not in [profileIds], with whatever
     * is in it. Never throws.
     */
    suspend fun deleteFoldersExcept(profileIds: Set<String>)

    companion object {
        const val PART_SUFFIX = ".epub.part"
        const val STAGED_SUFFIX = ".epub"
        const val DIRECTORY = "catalogue_staging"

        /** The name a checked file gets: the same generated name without ".part". */
        fun stagedPathFor(partPath: String): String = partPath.removeSuffix(PART_SUFFIX) + STAGED_SUFFIX
    }
}

interface StagingWriter {
    /** Appends the first [length] bytes of [buffer]. Throws when the disk refuses them. */
    fun write(buffer: ByteArray, length: Int)

    fun close()
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
