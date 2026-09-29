package com.retro99.cloudaccount.ui

internal fun canSubmitCloudAccountForm(
    mode: CloudAccountMode,
    email: String,
    password: String,
    tosAccepted: Boolean,
    isLoading: Boolean,
): Boolean = !isLoading &&
    email.isValidCloudAccountEmail() &&
    password.isNotBlank() &&
    (mode != CloudAccountMode.CreateAccount || tosAccepted)

internal fun String.isValidCloudAccountEmail(): Boolean {
    val trimmed = trim()
    val atIndex = trimmed.indexOf('@')
    if (atIndex <= 0 || atIndex != trimmed.lastIndexOf('@')) return false
    val localPart = trimmed.substring(0, atIndex)
    val domain = trimmed.substring(atIndex + 1)
    if (localPart.isEmpty() || domain.isEmpty()) return false
    val lastDotIndex = domain.lastIndexOf('.')
    if (lastDotIndex <= 0 || lastDotIndex == domain.lastIndex) return false
    return domain.substring(lastDotIndex + 1).length >= 2
}
