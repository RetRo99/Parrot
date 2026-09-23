package com.retro99.server.parrotcloud

import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock

/** Applies the server-owned file lifecycle feed to the per-profile local mirror. */
@Single
class ParrotCloudBookFileChangeApplier(
    @Provided private val cloudFilesDatabase: CloudFilesDatabase,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun apply(payload: JsonElement) {
        val file = json.decodeFromJsonElement<ParrotCloudBookFilePayload>(payload)
        val cloudBookId = file.cloudBookId ?: return
        val libraryBook = libraryBooksDatabase.getLibraryBookByCloudBookId(cloudBookId) ?: return
        val mediaType = file.mediaType ?: return
        val relativePath = file.relativePath.orEmpty()
        if (file.status == "none" || file.status == "removed") {
            cloudFilesDatabase.deleteFileState(libraryBook.libraryBookId, mediaType, relativePath)
            return
        }
        val fileId = file.cloudBookFileId ?: return
        cloudFilesDatabase.upsertFileState(
            CloudBookFileEntity(
                libraryBookId = libraryBook.libraryBookId,
                cloudBookId = cloudBookId,
                cloudBookFileId = fileId,
                mediaType = mediaType,
                relativePath = relativePath,
                fileName = file.fileName.orEmpty(),
                status = file.status.orEmpty(),
                sizeBytes = file.sizeBytes ?: 0,
                contentHash = file.contentHash.orEmpty(),
                contentHashAlgorithm = file.contentHashAlgorithm.orEmpty(),
                remoteRevision = file.remoteRevision ?: 0,
                updatedAt = Clock.System.now().toString(),
            ),
        )
    }
}

@Serializable
private data class ParrotCloudBookFilePayload(
    @SerialName("cloud_book_id")
    val cloudBookId: String? = null,
    @SerialName("cloud_book_file_id")
    val cloudBookFileId: String? = null,
    @SerialName("media_type")
    val mediaType: String? = null,
    @SerialName("relative_path")
    val relativePath: String? = null,
    @SerialName("file_name")
    val fileName: String? = null,
    val status: String? = null,
    @SerialName("size_bytes")
    val sizeBytes: Long? = null,
    @SerialName("content_hash")
    val contentHash: String? = null,
    @SerialName("content_hash_algorithm")
    val contentHashAlgorithm: String? = null,
    @SerialName("remote_revision")
    val remoteRevision: Long? = null,
)
