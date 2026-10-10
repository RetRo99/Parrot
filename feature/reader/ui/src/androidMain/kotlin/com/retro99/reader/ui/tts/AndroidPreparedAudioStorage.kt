package com.retro99.reader.ui.tts

import com.retro99.reader.domain.tts.PreparedAudioStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/** The Settings screen's view of the prepared store. */
@Single(binds = [PreparedAudioStorage::class])
class AndroidPreparedAudioStorage(private val prepared: TtsPreparedAudioStore) : PreparedAudioStorage {

    override suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        runCatching { prepared.store.totalSize() }.getOrDefault(0L)
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) { runCatching { prepared.store.deleteAll() } }
    }
}
