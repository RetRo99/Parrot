package com.retro99.user.implementation

import co.touchlab.kermit.Logger
import com.retro99.base.AppInitializer
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.runBlocking
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

internal object DefaultUserInitializerLogMessages {
    const val NO_PROFILES_FOUND = "No user profiles found, creating default profile"
    const val DEFAULT_PROFILE_CREATED = "Created and activated default profile"
    const val NO_ACTIVE_PROFILE = "No active profile, activating first available profile"
    const val EXISTING_PROFILE_ACTIVATED = "Activated an existing profile"
    const val INCONSISTENT_PROFILE_STATE = "Inconsistent state: hasProfiles=true but no profiles found"
    const val ACTIVE_PROFILE_ALREADY_SET = "Active profile already set"
}

/**
 * Default user profile name.
 * This is the name shown for the automatically created profile.
 */
private const val DEFAULT_USER_NAME = "Default"

/**
 * Initializer that ensures a default user profile exists and is active.
 *
 * On first app launch (when no profiles exist), this creates a "Default" profile
 * and sets it as the active profile. This ensures the app always has an active
 * user context for database and server operations.
 *
 * For existing users (migration scenario), if profiles exist but none is active,
 * the first profile will be set as active.
 */
@Single(binds = [AppInitializer::class])
class DefaultUserInitializer(
    @Provided private val userRegistry: UserRegistry,
) : AppInitializer {

    private val logger = Logger.withTag("DefaultUserInitializer")

    override fun initialize() {
        runBlocking {
            ensureDefaultUserExists()
        }
    }

    private suspend fun ensureDefaultUserExists() {
        // Check if any profiles exist
        if (!userRegistry.hasProfiles()) {
            // First launch - create default profile with fixed ID
            logger.i { DefaultUserInitializerLogMessages.NO_PROFILES_FOUND }
            val defaultProfile = userRegistry.createProfile(
                id = UserRegistry.DEFAULT_USER_ID,
                name = DEFAULT_USER_NAME,
            )
            userRegistry.setActiveProfile(defaultProfile.id)
            logger.i { DefaultUserInitializerLogMessages.DEFAULT_PROFILE_CREATED }
            return
        }

        // Profiles exist - ensure one is active
        if (!userRegistry.isProfileActive()) {
            logger.i { DefaultUserInitializerLogMessages.NO_ACTIVE_PROFILE }
            val profiles = userRegistry.getAllProfiles()
            val firstProfile = profiles.firstOrNull()
            if (firstProfile != null) {
                userRegistry.setActiveProfile(firstProfile.id)
                logger.i { DefaultUserInitializerLogMessages.EXISTING_PROFILE_ACTIVATED }
            } else {
                // Edge case: hasProfiles() returned true but getAllProfiles() is empty
                // This shouldn't happen, but handle it gracefully
                logger.w { DefaultUserInitializerLogMessages.INCONSISTENT_PROFILE_STATE }
                val defaultProfile = userRegistry.createProfile(
                    id = UserRegistry.DEFAULT_USER_ID,
                    name = DEFAULT_USER_NAME,
                )
                userRegistry.setActiveProfile(defaultProfile.id)
                logger.i { DefaultUserInitializerLogMessages.DEFAULT_PROFILE_CREATED }
            }
        } else {
            logger.d { DefaultUserInitializerLogMessages.ACTIVE_PROFILE_ALREADY_SET }
        }
    }
}
