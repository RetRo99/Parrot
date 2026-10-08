package com.retro99.server.implementation

import com.retro99.preferences.api.*
import com.retro99.server.api.*
import com.retro99.user.api.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.*

class ServerRegistryPersistenceTest {
    @Test fun catalogue_address_is_exact_through_add_update_and_restart() = runTest {
        val addresses = listOf("https://catalogue.example/opds/", "https://catalogue.example/opds", "https://catalogue.example/opds/?key=secret&library=2", "https://catalogue.example/a/deep/library/feed/")
        val preferences = RegistryPreferences()
        val registry = registry(preferences)
        for ((index, address) in addresses.withIndex()) {
            val added = registry.addServerWithId("source-$index", "Catalogue", ServerType.Opds, address)
            assertEquals(address, added.baseUrl)
            assertEquals(address, registry(preferences).getServer(added.id)?.baseUrl)
            val updatedAddress = addresses[(index + 1) % addresses.size]
            registry.updateServer(added.copy(baseUrl = updatedAddress))
            assertEquals(updatedAddress, registry.getServer(added.id)?.baseUrl)
            assertEquals(updatedAddress, registry(preferences).getServer(added.id)?.baseUrl)
        }
    }

    @Test fun existing_types_still_trim_trailing_slashes_on_add() = runTest {
        val registry = registry(RegistryPreferences())
        for (type in listOf(ServerType.Storyteller, ServerType.Audiobookshelf, ServerType.Local, ServerType.ParrotCloud)) {
            assertEquals("https://library.example", registry.addServerWithId(type.identifier, type.displayName, type, "https://library.example///").baseUrl)
        }
    }
    private fun registry(preferences: RegistryPreferences, profile: String = "profile-a") =
        registryWithOwnStores(preferences, RegistryUser(profile))

    @Test fun adding_catalogue_keeps_registered_servers_readable_by_old_decoder() = runTest {
        val preferences = RegistryPreferences()
        val registry = registry(preferences)
        val existing = listOf(ServerType.Storyteller, ServerType.Audiobookshelf, ServerType.ParrotCloud, ServerType.Local)
        existing.forEach { registry.addServerWithId(it.identifier, it.displayName, it, "https://library.example/") }
        val before = preferences.getStringOrNull(PreferencesKey.UserScoped("profile-a", "RegisteredServers"))
        registry.addServerWithId("catalogue", "Catalogue", ServerType.fromIdentifier("opds")!!, "https://catalogue.example/opds/")
        val stored = preferences.getStringOrNull(PreferencesKey.UserScoped("profile-a", "RegisteredServers"))!!
        assertEquals(before, stored)
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<List<LegacyConfig>>(stored)
        assertEquals(existing.map { it.identifier }.toSet(), decoded.map { it.id }.toSet())
        assertEquals(5, registry(preferences).getAllServers().size)
        assertTrue(registry(preferences, "profile-b").getAllServers().isEmpty())
    }

    @Test fun catalogue_add_update_remove_roll_back_on_failed_write() = runTest {
        val preferences = RegistryPreferences()
        val registry = registry(preferences)
        val opds = ServerType.fromIdentifier("opds")!!
        preferences.failWrites = true
        assertFailsWith<IllegalStateException> { registry.addServerWithId("catalogue", "Catalogue", opds, "https://catalogue.example/opds") }
        assertTrue(registry.getAllServers().isEmpty())
        preferences.failWrites = false
        val source = registry.addServerWithId("catalogue", "Catalogue", opds, "https://catalogue.example/opds")
        preferences.failWrites = true
        assertFailsWith<IllegalStateException> { registry.updateServer(source.copy(name = "Changed")) }
        assertEquals(source, registry.getServer(source.id))
        assertFailsWith<IllegalStateException> { registry.removeServer(source.id) }
        assertEquals(source, registry.getServer(source.id))
        assertEquals(source, registry(preferences).getServer(source.id))
        preferences.failWrites = false
        registry.updateServer(source.copy(name = "Changed"))
        assertEquals("Changed", registry(preferences).getServer(source.id)?.name)
        registry.removeServer(source.id)
        assertTrue(registry(preferences).getAllServers().isEmpty())
    }

    @Serializable private enum class LegacyType {
        @kotlinx.serialization.SerialName("storyteller") Storyteller,
        @kotlinx.serialization.SerialName("audiobookshelf") Audiobookshelf,
        @kotlinx.serialization.SerialName("parrot-cloud") ParrotCloud,
        @kotlinx.serialization.SerialName("local") Local,
    }
    @Serializable private data class LegacyConfig(val id: String, val name: String, val type: LegacyType, val baseUrl: String, val addedAt: Long, val lastConnectedAt: Long? = null)
}

internal class RegistryPreferences : Preferences {
    private val values = mutableMapOf<String, String>()
    var failWrites = false
    override fun getStringOrNull(key: PreferencesKey) = values[key.name]
    override fun putString(key: PreferencesKey, value: String) {
        check(!failWrites) { "write failed" }
        values[key.name] = value
    }
    override fun observeStringOrNull(key: PreferencesKey): Flow<String?> = flowOf(getStringOrNull(key))
    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean) = defaultValue
    override fun putBoolean(key: PreferencesKey, value: Boolean) = error("unused")
    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean) = flowOf(defaultValue)
    override fun getLong(key: PreferencesKey, defaultValue: Long) = defaultValue
    override fun putLong(key: PreferencesKey, value: Long) = error("unused")
    override fun remove(key: PreferencesKey) { values.remove(key.name) }
}

internal class RegistryUser(private val profile: String) : UserRegistry {
    private val user = UserProfile(id = profile, name = profile, createdAt = 0L)
    override fun observeAllProfiles() = flowOf(listOf(user))
    override suspend fun getAllProfiles() = listOf(user)
    override suspend fun createProfile(id: String?, name: String, avatarId: Int?): UserProfile = error("unused")
    override suspend fun updateProfile(profile: UserProfile) = error("unused")
    override suspend fun deleteProfile(profileId: String) = error("unused")
    override suspend fun getProfile(profileId: String) = user.takeIf { it.id == profileId }
    override fun observeActiveProfile(): Flow<UserProfile?> = flowOf(user)
    override suspend fun getActiveProfile() = user
    override fun getActiveProfileId() = profile
    override suspend fun setActiveProfile(profileId: String) = error("unused")
    override suspend fun clearActiveProfile() = error("unused")
    override suspend fun hasProfiles() = true
    override fun isProfileActive() = true
}

/** A registry for tests that do not look at catalogue account details, status or work. */
internal fun registryWithOwnStores(preferences: Preferences, users: UserRegistry) = ServerRegistryImpl(
    preferences,
    users,
    emptyList(),
    OpdsCredentialStoreImpl(preferences),
    CatalogueAccessStoreImpl(preferences),
    emptyList(),
)
