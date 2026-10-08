package com.retro99.server.api

/** A one-page, non-persistent check used before registering a catalogue source. */
fun interface CatalogueConnectionValidator {
    suspend fun validate(address: String, account: OpdsAccountDetails?): CatalogueConnectionResult
}

sealed interface CatalogueConnectionResult {
    data object Accepted : CatalogueConnectionResult
    data object WebPage : CatalogueConnectionResult
    data object Unreachable : CatalogueConnectionResult
    data object NotCatalogue : CatalogueConnectionResult
    data object NeedsBasic : CatalogueConnectionResult
    data class UnsupportedSignIn(val rootAnswered401: Boolean) : CatalogueConnectionResult
    data object CertificateFailure : CatalogueConnectionResult
    data object InvalidCredentials : CatalogueConnectionResult
}
