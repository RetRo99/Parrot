package com.retro99.server.storyteller

import com.retro99.cloud.implementation.transfer.BearerTusRequestHeaderPolicy
import com.retro99.cloud.implementation.transfer.TusAccessTokenProvider
import com.retro99.cloud.implementation.transfer.TusMetadataEncoder
import com.retro99.cloud.implementation.transfer.TusRequestHeaderPolicy
import com.retro99.cloud.implementation.transfer.TusUploadMetadata
import com.retro99.cloud.implementation.transfer.TusRequestType
import org.koin.core.annotation.Single

@Single
class StorytellerTusMetadataEncoder : TusMetadataEncoder {
    override fun encode(metadata: TusUploadMetadata): Map<String, String> {
        require(metadata.bookUuid.isNotBlank()) { "Storyteller uploads require bookUuid metadata" }
        require(metadata.fileName.isNotBlank()) { "Storyteller uploads require filename metadata" }
        require(metadata.totalFiles > 0) { "Storyteller uploads require a positive totalFiles value" }
        return linkedMapOf(
            "bookUuid" to metadata.bookUuid,
            "filename" to metadata.fileName,
            "filetype" to metadata.contentType(),
            "totalFiles" to metadata.totalFiles.toString(),
        )
    }
}

/** Storyteller protects HEAD as well as create, patch, and delete with bearer auth. */
class StorytellerTusRequestHeaderPolicy(
    accessTokenProvider: TusAccessTokenProvider,
) : TusRequestHeaderPolicy {
    private val delegate = BearerTusRequestHeaderPolicy(accessTokenProvider)

    override fun headersFor(requestType: TusRequestType): Map<String, String> =
        delegate.headersFor(requestType)
}
