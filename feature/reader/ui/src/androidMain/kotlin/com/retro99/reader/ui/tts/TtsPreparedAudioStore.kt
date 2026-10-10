package com.retro99.reader.ui.tts

import android.content.Context
import java.io.File
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** App-owned files, deliberately separate from the disposable sentence cache. */
@Single
class TtsPreparedAudioStore(@Provided private val context: Context) {
    internal val store: TtsPreparedStore by lazy {
        TtsPreparedStore(File(context.filesDir, "tts-prepared"))
    }
}
