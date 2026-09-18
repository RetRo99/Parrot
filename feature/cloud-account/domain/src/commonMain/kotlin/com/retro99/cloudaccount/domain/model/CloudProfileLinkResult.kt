package com.retro99.cloudaccount.domain.model

sealed interface CloudProfileLinkResult {
    data class Linked(
        val link: CloudProfileLink,
    ) : CloudProfileLinkResult

    data class LocalProfileAlreadyLinked(
        val link: CloudProfileLink,
    ) : CloudProfileLinkResult

    data class CloudAccountAlreadyLinked(
        val link: CloudProfileLink,
    ) : CloudProfileLinkResult
}
