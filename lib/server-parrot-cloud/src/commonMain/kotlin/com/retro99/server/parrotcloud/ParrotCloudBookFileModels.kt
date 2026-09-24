package com.retro99.server.parrotcloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ParrotCloudBookFileRpcResponse(
    val status: String,
    val reason: String? = null,
    @SerialName("upload_id")
    val uploadId: String? = null,
    @SerialName("cloud_book_file_id")
    val cloudBookFileId: String? = null,
    @SerialName("storage_path")
    val storagePath: String? = null,
    @SerialName("upload_url")
    val uploadUrl: String? = null,
    @SerialName("size_bytes")
    val sizeBytes: Long? = null,
    @SerialName("content_hash")
    val contentHash: String? = null,
    @SerialName("content_hash_algorithm")
    val contentHashAlgorithm: String? = null,
    val revision: Long? = null,
    @SerialName("used_bytes")
    val usedBytes: Long? = null,
    @SerialName("quota_bytes")
    val quotaBytes: Long? = null,
    @SerialName("retry_after_ms")
    val retryAfterMillis: Long? = null,
    @SerialName("media_type")
    val mediaType: String? = null,
    @SerialName("relative_path")
    val relativePath: String? = null,
    @SerialName("file_name")
    val fileName: String? = null,
)
