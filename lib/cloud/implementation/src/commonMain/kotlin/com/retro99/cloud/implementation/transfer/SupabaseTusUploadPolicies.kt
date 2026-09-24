package com.retro99.cloud.implementation.transfer

import com.retro99.cloud.implementation.CloudConfiguration
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class SupabaseTusMetadataEncoder : TusMetadataEncoder {
    override fun encode(metadata: TusUploadMetadata): Map<String, String> = linkedMapOf(
        "bucketName" to BOOK_FILES_BUCKET,
        "objectName" to metadata.targetPath,
        "contentType" to metadata.contentType(),
        "cacheControl" to CACHE_CONTROL_SECONDS,
        "metadata" to "{\"sha256\":\"${metadata.contentHash}\"}",
    )

    private companion object {
        const val BOOK_FILES_BUCKET = "book-files"
        const val CACHE_CONTROL_SECONDS = "3600"
    }
}

@Single
class SupabaseTusRequestHeaderPolicy(
    @Provided private val configuration: CloudConfiguration,
    @Provided private val accessTokenProvider: TusAccessTokenProvider,
) : TusRequestHeaderPolicy {
    private val delegate = BearerTusRequestHeaderPolicy(
        accessTokenProvider = accessTokenProvider,
        authenticatedRequests = setOf(TusRequestType.Create, TusRequestType.Patch, TusRequestType.Delete),
        apiKey = configuration.publishableKey,
        apiKeyRequests = setOf(TusRequestType.Create, TusRequestType.Patch, TusRequestType.Delete),
    )

    override fun headersFor(requestType: TusRequestType): Map<String, String> =
        delegate.headersFor(requestType)
}
