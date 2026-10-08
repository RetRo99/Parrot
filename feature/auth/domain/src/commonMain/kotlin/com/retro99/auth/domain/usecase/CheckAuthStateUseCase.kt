package com.retro99.auth.domain.usecase

import com.retro99.base.server.ServerType
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerConfig
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class CheckAuthStateUseCase(
    @Provided private val serverRegistry: ServerRegistry,
    @Provided private val preferences: Preferences,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
) {
    /**
     * Existing server setups, Cloud-linked profiles, guest users, and local libraries
     * should go straight to the library instead of seeing first-run onboarding again.
     */
    suspend operator fun invoke(): Boolean {
        val hasSkippedLogin = preferences.getBoolean(PreferencesKey.SkippedLogin, defaultValue = false)
        val hasImportedBooks = libraryBooksDatabase.countLibraryBooksWithDeviceFiles() > 0
        val hasConfiguredRemoteServer = hasConfiguredRemoteSetup(serverRegistry.getAllServers())
        val hasAuthenticatedRemoteServer = serverRegistry.getAuthenticatedServers()
            .any { it.type != ServerType.Local }

        return shouldBypassWelcome(
            hasSkippedLogin = hasSkippedLogin,
            hasImportedBooks = hasImportedBooks,
            hasConfiguredRemoteServer = hasConfiguredRemoteServer,
            hasAuthenticatedRemoteServer = hasAuthenticatedRemoteServer,
        )
    }
}

/** A public (or temporarily turned-off) catalogue is a configured setup, not a missing login. */
internal fun hasConfiguredRemoteSetup(sources: List<ServerConfig>): Boolean = sources.any { it.type != ServerType.Local }

internal fun shouldBypassWelcome(
    hasSkippedLogin: Boolean,
    hasImportedBooks: Boolean,
    hasConfiguredRemoteServer: Boolean,
    hasAuthenticatedRemoteServer: Boolean,
): Boolean = hasSkippedLogin ||
    hasImportedBooks ||
    hasConfiguredRemoteServer ||
    hasAuthenticatedRemoteServer
