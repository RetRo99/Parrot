package com.retro99.parrot.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.catalogue.data.CatalogueStagingFiles
import com.retro99.catalogue.data.StagingWriter
import com.retro99.cloud.implementation.CloudConfiguration
import com.retro99.cloud.implementation.CloudOAuthUrlLauncher
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.SqlDriverFactory
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.koin.core.Koin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.koinApplication
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/**
 * The app's own Koin graph: every module [ParrotKoinApp] lists, wired by the generated code.
 * Only what cannot exist on a build machine is replaced: the encrypted preferences, the
 * database file, the network engine and the staging folder (they need an Android Context or
 * the internet). Nothing between those edges is hand-built.
 */
internal class RealAppGraph(
    private val respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("No network in this test") },
) : AutoCloseable {
    val stagingRoot: File = Files.createTempDirectory("real-graph-staging").toFile()
    private val databaseRoot: File = Files.createTempDirectory("real-graph-database").toFile()
    private val drivers = mutableListOf<SqlDriver>()

    private val application = koinApplication<ParrotKoinApp> {
        // Later definitions win.
        allowOverride(true)
        modules(
            module {
                single<Preferences> { MemoryPreferences() }
                single<SqlDriverFactory> {
                    object : SqlDriverFactory {
                        // One file per profile, like the app: the database is opened more than
                        // once in a run and must be the same database each time.
                        override fun createDriver(userId: String): SqlDriver {
                            val file = File(databaseRoot, "$userId.db")
                            val isNew = !file.exists()
                            return JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").also { driver ->
                                if (isNew) AppDatabase.Schema.create(driver)
                                drivers += driver
                            }
                        }

                        override fun deleteUserDatabase(userId: String) = File(databaseRoot, "$userId.db").delete()
                    }
                }
                single<HttpClientEngineFactory<*>> {
                    object : HttpClientEngineFactory<MockEngineConfig> {
                        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                            MockEngine { request -> respond(request) }
                    }
                }
                single<CatalogueStagingFiles> { TempStagingFiles(stagingRoot) }
                // Read from the Android manifest in the app. Blank means "Parrot Cloud is not set up".
                single { CloudConfiguration(supabaseUrl = "", publishableKey = "") }
                single<CloudOAuthUrlLauncher> {
                    object : CloudOAuthUrlLauncher {
                        override fun open(url: String) = error("No browser in this test")
                    }
                }
            },
        )
    }

    val koin: Koin get() = application.koin

    override fun close() {
        application.close()
        drivers.forEach { driver -> runCatching { driver.close() } }
        stagingRoot.deleteRecursively()
        databaseRoot.deleteRecursively()
    }
}

/** Reads a private constructor property, to compare instances without changing production code. */
internal fun Any.privateField(name: String): Any? =
    javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

private class MemoryPreferences : Preferences {
    private val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun getStringOrNull(key: PreferencesKey) = values.value[key.name]
    override fun putString(key: PreferencesKey, value: String) { values.value += (key.name to value) }
    override fun observeStringOrNull(key: PreferencesKey): Flow<String?> = values.map { it[key.name] }
    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean) = getStringOrNull(key)?.toBoolean() ?: defaultValue
    override fun putBoolean(key: PreferencesKey, value: Boolean) = putString(key, value.toString())
    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean) =
        observeStringOrNull(key).map { it?.toBoolean() ?: defaultValue }
    override fun getLong(key: PreferencesKey, defaultValue: Long) = getStringOrNull(key)?.toLong() ?: defaultValue
    override fun putLong(key: PreferencesKey, value: Long) = putString(key, value.toString())
    override fun remove(key: PreferencesKey) { values.value -= key.name }
}

private class TempStagingFiles(private val root: File) : CatalogueStagingFiles {
    override fun newPartPath(profileId: String): String =
        File(File(root, profileId), "${UUID.randomUUID()}${CatalogueStagingFiles.PART_SUFFIX}").absolutePath

    override suspend fun openForWriting(path: String): StagingWriter {
        File(path).parentFile?.mkdirs()
        val output = FileOutputStream(path, false)
        return object : StagingWriter {
            override fun write(buffer: ByteArray, length: Int) = output.write(buffer, 0, length)
            override fun close() = output.close()
        }
    }

    override suspend fun freeSpaceBytes(): Long? = null
    override suspend fun size(path: String) = File(path).length()
    override suspend fun sha256(path: String): String =
        MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()).joinToString("") { "%02x".format(it) }
    override suspend fun rename(from: String, to: String) = File(from).renameTo(File(to))
    override suspend fun delete(path: String) { File(path).delete() }
}
