package com.retro99.dictionary

import app.cash.sqldelight.db.SqlDriver
import com.retro99.packs.PackDownloader
import com.retro99.packs.PackFile
import com.retro99.packs.PackManager
import com.retro99.packs.PackManifest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

data class DictionaryPackState(
    val installed: Boolean = false,
    val bundled: Boolean = false,
    val downloading: Boolean = false,
    val percent: Int = 0,
    val error: String? = null,
    val updateAvailable: Boolean = false,
    val sizeBytes: Long = ENGLISH_PACK.files.sumOf { it.size },
    val updateSizeBytes: Long = sizeBytes,
)

val ENGLISH_PACK = PackManifest("dictionary-en", "oewn-2025-1", listOf(PackFile(
    "english.sqlite",
    "https://github.com/RetRo99/tts-models/releases/download/oewn-2025-1/english.sqlite",
    21_991_424,
    "850da5c0f3efea6d911a17908155091230e4867a437cfa61bb3d6249e1efc053",
)))

/** Device-scoped pack; download lifetime is independent of the selection and reader. */
@Single
class DictionaryService(@Provided private val platform: DictionaryPlatform) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient { install(HttpTimeout) { connectTimeoutMillis = 15_000; socketTimeoutMillis = 30_000 } }
    private val manager = PackManager(platform.packRoot.toPath(), PackDownloader(client), platform::availableBytes)
    private val bundled = BundledDictionaryPack(platform.packRoot.toPath(), platform::availableBytes)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var driver: SqlDriver? = null
    private var database: DictionaryDatabase? = null
    private var readers = 0
    private var latest = ENGLISH_PACK
    private val mutableState = MutableStateFlow(DictionaryPackState(installed = true,
        bundled = manager.activeFile(ENGLISH_PACK.id, "english.sqlite") == null))
    val state: StateFlow<DictionaryPackState> = mutableState

    suspend fun acquire() = withContext(Dispatchers.IO) { mutex.withLock {
        // Prepare the file when the reader opens, keeping the SQLite index lazy.
        if (mutableState.value.bundled) {
            try { bundled.file() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message) }
        }
        readers++
    } }
    suspend fun release() = withContext(Dispatchers.IO) { mutex.withLock {
        readers = (readers - 1).coerceAtLeast(0)
        if (readers == 0) closeIndex()
    } }

    /** No network or download is reachable from this method. */
    suspend fun lookup(selection: String): DictionaryEntry? = withContext(Dispatchers.IO) {
        val word = dictionaryWord(selection) ?: return@withContext null
        mutex.withLock {
            try {
                val downloaded = manager.activeFile(ENGLISH_PACK.id, "english.sqlite")
                val path = downloaded ?: bundled.file()
                val db = database ?: platform.open(path.toString()).let { opened ->
                    driver = opened
                    DictionaryDatabase(opened).also {
                        check(it.dictionaryQueries.version().executeAsOne() ==
                            if (downloaded != null) manager.activeVersion(ENGLISH_PACK.id) else ENGLISH_PACK.version)
                        database = it
                    }
                }
                lookupDictionary(word,
                    exact = { key -> db.dictionaryQueries.entry(key).executeAsOneOrNull()?.let { json.decodeFromString<DictionaryEntry>(it) } },
                    forms = { key -> db.dictionaryQueries.forms(key).executeAsList() })
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                closeIndex()
                mutableState.value = mutableState.value.copy(error = error.message?.takeIf { it.startsWith("Not enough storage") }
                    ?: "Dictionary could not be opened. Reinstall the app or remove the downloaded update.")
                null
            }
        }
    }

    fun download() {
        if (mutableState.value.downloading) return
        mutableState.value = mutableState.value.copy(downloading = true, error = null, percent = 0)
        scope.launch {
            try {
                val installing = latest
                manager.install(installing, onProgress = { percent ->
                    // Five-percent steps avoid repeated e-ink refreshes.
                    val coarse = percent / 5 * 5
                    if (coarse != mutableState.value.percent) mutableState.value = mutableState.value.copy(percent = coarse)
                }, validateInstalled = { directory ->
                    platform.open((directory / "english.sqlite").toString()).let { opened ->
                        try {
                            check(DictionaryDatabase(opened).dictionaryQueries.version().executeAsOne() == installing.version) { "Dictionary version is invalid. Try again." }
                        } finally { opened.close() }
                    }
                })
                mutex.withLock { closeIndex() }
                mutableState.value = DictionaryPackState(installed = true, sizeBytes = installing.files.sumOf { it.size })
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableState.value = mutableState.value.copy(downloading = false,
                    error = error.message?.takeIf { it.startsWith("Not enough storage") } ?: "Could not download dictionary. Try again when online.")
            }
        }
    }

    fun remove() {
        if (mutableState.value.downloading) return
        scope.launch {
            mutex.withLock { closeIndex(); manager.remove(ENGLISH_PACK.id) }
            mutableState.value = DictionaryPackState(installed = true, bundled = true,
                updateAvailable = latest.version != ENGLISH_PACK.version,
                updateSizeBytes = latest.files.sumOf { it.size })
        }
    }

    /** Explicit settings action. Merely opening a reader or selecting text never checks online. */
    fun checkForUpdates() {
        scope.launch {
            try {
                val response = client.get("https://github.com/RetRo99/tts-models/releases/download/dictionary/english-manifest.json")
                check(response.status.value == 200)
                val manifest = json.decodeFromString<PackManifest>(response.bodyAsText())
                manifest.validate()
                check(manifest.id == ENGLISH_PACK.id && manifest.files.size == 1 && manifest.files[0].path == "english.sqlite")
                latest = manifest
                mutableState.value = mutableState.value.copy(updateAvailable =
                    (manager.activeVersion(manifest.id) ?: ENGLISH_PACK.version) != manifest.version,
                    updateSizeBytes = manifest.files.sumOf { it.size }, error = null)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "Could not check for updates. Try again when online.") }
        }
    }

    private fun closeIndex() { database = null; driver?.close(); driver = null }
}
