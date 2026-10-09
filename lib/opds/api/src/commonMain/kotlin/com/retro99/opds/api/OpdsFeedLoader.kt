package com.retro99.opds.api

import com.retro99.opds.api.model.OpdsDocument
import com.retro99.opds.api.model.OpdsRejection

interface OpdsFeedLoader {
    suspend fun load(key: OpdsCacheKey, request: OpdsRequest): OpdsLoadResult
}

sealed interface OpdsLoadResult {
    data class Document(val document: OpdsDocument, val fromCache: Boolean, val crossOriginPrivateNetwork: Boolean = false) : OpdsLoadResult
    /**
     * The catalogue could not be reached and this is the copy saved when the page was last
     * opened, at [storedAtMillis]. Never returned for any other failure.
     */
    data class SavedCopy(val document: OpdsDocument, val storedAtMillis: Long) : OpdsLoadResult
    data class FetchFailure(val error: OpdsTransportError, val crossOriginPrivateNetwork: Boolean = false) : OpdsLoadResult
    data class ParseFailure(val rejection: OpdsRejection, val responseContentType: String? = null) : OpdsLoadResult
    data object NotModifiedWithoutCache : OpdsLoadResult
}
