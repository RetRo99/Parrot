package com.retro99.home.ui.appsettings

import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AppSettingsAnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.user.api.UserProfile

internal fun isDuplicateProfileName(
    candidate: String,
    profiles: List<UserProfile>,
    excludingProfileId: String? = null,
): Boolean {
    val normalizedCandidate = normalizeProfileName(candidate)
    if (normalizedCandidate.isEmpty()) return false

    return profiles.any { profile ->
        profile.id != excludingProfileId && normalizeProfileName(profile.name) == normalizedCandidate
    }
}

internal fun duplicateProfileOrdinals(profiles: List<UserProfile>): Map<String, Int> = buildMap {
    profiles
        .filter { normalizeProfileName(it.name).isNotEmpty() }
        .groupBy { normalizeProfileName(it.name) }
        .values
        .filter { it.size > 1 }
        .forEach { duplicates ->
            duplicates
                .sortedWith(compareBy<UserProfile> { it.createdAt }.thenBy { it.id })
                .forEachIndexed { index, profile -> put(profile.id, index + 1) }
        }
}

private fun normalizeProfileName(name: String): String = name.trim().lowercase()

internal fun reportDuplicateProfileNameRejected(
    analytics: Analytics,
    operation: AppSettingsAnalyticsEvent.ProfileOperation,
) {
    analytics.logEvent(
        AppSettingsAnalyticsEvent.ProfileNameValidationFailed(
            profileOperation = operation,
            reason = AppSettingsAnalyticsEvent.ProfileNameValidationReason.DuplicateName,
        ),
    )
    analytics.logBreadcrumb(
        DiagnosticContext(
            screen = "app_settings",
            action = operation.action,
            operation = operation.operation,
            stage = "validation",
            outcome = "rejected",
            reasonCode = "duplicate_name",
        ),
    )
}
