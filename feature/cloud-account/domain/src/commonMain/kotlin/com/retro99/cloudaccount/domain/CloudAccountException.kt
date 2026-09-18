package com.retro99.cloudaccount.domain

sealed class CloudAccountException(message: String) : Exception(message) {
    class NotConfigured : CloudAccountException("Cloud account is not configured")

    class ProfileAlreadyLinked :
        CloudAccountException("Cloud profile is already linked to a different account")
}
