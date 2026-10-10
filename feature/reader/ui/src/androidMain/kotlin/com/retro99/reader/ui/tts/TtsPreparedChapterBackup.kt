package com.retro99.reader.ui.tts

import android.content.Context
import com.retro99.analytics.api.Analytics
import com.retro99.base.AppInitializer
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.reader.ui.reader.PreparedChapterBackupState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * The app-wide home of prepared-chapter backup, and the one thing that calls
 * the transfer engine for a prepared chapter.
 *
 * It is also an [AppInitializer]: a chapter prepared while the device was on
 * mobile data, or before the account was linked, is only ever picked up by the
 * sweep at app start, so that sweep has to live where the app starts and not in
 * the reader screen it outlives.
 */
@Single(binds = [AppInitializer::class, TtsPreparedChapterBackup::class])
internal class TtsPreparedChapterBackup(
    @Provided private val context: Context,
    private val prepared: TtsPreparedAudioStore,
    private val preparation: TtsChapterPreparationJob,
    @Provided private val transfers: BookFileTransferManager,
    private val account: PreparedBackupAccount,
    private val network: PreparedBackupNetwork,
    @Provided private val analytics: Analytics,
) : AppInitializer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // The preparation job knows nothing about the cloud; backup attaches
        // itself to it, so preparation is unchanged when backup never exists.
        preparation.onPrepared = ::backUpWhenPrepared
    }

    private val queue: PreparedChapterBackupQueue by lazy {
        PreparedChapterBackupQueue(
            store = prepared.store,
            archive = TtsPreparedChapterArchive(),
            outbox = File(context.filesDir, OUTBOX).apply { mkdirs() },
            transfers = transfers,
            account = account,
            network = network,
            scope = scope,
            analytics = analytics,
        )
    }

    /** At app start, for every chapter prepared earlier. */
    override fun initialize() {
        scope.launch { queue.backUpEverythingPrepared() }
    }

    /** Right after a chapter finishes preparing. */
    fun backUpWhenPrepared(id: PreparedChapterId, settings: PreparedVoiceSettings) {
        scope.launch { queue.backUp(id, settings) }
    }

    suspend fun stateOf(id: PreparedChapterId, settings: PreparedVoiceSettings): PreparedChapterBackupState =
        queue.stateOf(id, settings)

    /** The chapter is being deleted from the row: its cloud copy goes too. */
    suspend fun remove(id: PreparedChapterId, settings: PreparedVoiceSettings) =
        queue.remove(id, settings)

    private companion object {
        const val OUTBOX = "tts-prepared-outbox"
    }
}
