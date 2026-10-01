package com.retro99.reader.data.linked

import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.GetUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.retro99.reader.domain.linked.LinkedResumeDismissals
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** Dismissals in the active profile's preferences, newest last, at most 200. */
@Single(binds = [LinkedResumeDismissals::class])
class PreferencesLinkedResumeDismissals(
    @Provided private val getUserPreferenceUseCase: GetUserPreferenceUseCase,
    @Provided private val saveUserPreferenceUseCase: SaveUserPreferenceUseCase,
) : LinkedResumeDismissals {
    private val mutex = Mutex()

    override suspend fun isDismissed(entry: String): Boolean = entry in entries()

    override suspend fun dismiss(entry: String) = mutex.withLock {
        save((entries() - entry) + entry)
    }

    override suspend fun renameCopy(fromKey: String, intoKey: String) = mutex.withLock {
        save(entries().map { entry -> renamed(entry, fromKey, intoKey) }.distinct())
    }

    private fun entries(): List<String> =
        getUserPreferenceUseCase<List<String>>(PreferencesKey.DismissedLinkedResume).orEmpty()

    private fun save(entries: List<String>) {
        saveUserPreferenceUseCase(
            PreferencesKey.DismissedLinkedResume,
            entries.takeLast(LinkedResumeDismissals.MAX_ENTRIES),
        )
    }
}

/** [entry] with the target or source copy [fromKey] renamed to [intoKey]. */
internal fun renamed(entry: String, fromKey: String, intoKey: String): String {
    val parts = entry.split('|')
    if (parts.size < 3) return entry
    val target = if (parts[0] == fromKey) intoKey else parts[0]
    val source = if (parts[1] == fromKey) intoKey else parts[1]
    return (listOf(target, source) + parts.drop(2)).joinToString("|")
}
