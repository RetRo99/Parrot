package com.retro99.reader.ui.tts

import java.io.File

/** Where a generated sentence's measurement goes. */
internal fun interface TtsPreparationSpeedLog {
    fun record(voiceId: String?, sample: TtsPreparationSample)
}

/**
 * The device's own preparation speed, in one small file of its own. Deliberately not in any
 * chapter's `manifest.json`: that format is checked by the cloud archive and does not change.
 */
@Suppress("UnusedPrivateProperty")
internal class TtsPreparationSpeedFile(private val file: File) : TtsPreparationSpeedLog {

    fun read(): TtsPreparationSpeedRecord = TtsPreparationSpeedRecord()

    override fun record(voiceId: String?, sample: TtsPreparationSample) = Unit
}
