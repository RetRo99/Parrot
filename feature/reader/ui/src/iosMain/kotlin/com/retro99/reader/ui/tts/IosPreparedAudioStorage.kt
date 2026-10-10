package com.retro99.reader.ui.tts

import com.retro99.reader.domain.tts.PreparedAudioStorage
import org.koin.core.annotation.Single

/** iPhone prepares no chapters, so there is nothing to total and nothing to delete. */
@Single(binds = [PreparedAudioStorage::class])
class IosPreparedAudioStorage : PreparedAudioStorage {

    override suspend fun totalBytes(): Long = 0L

    override suspend fun deleteAll() = Unit
}
