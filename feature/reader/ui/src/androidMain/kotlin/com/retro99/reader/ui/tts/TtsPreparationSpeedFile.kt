package com.retro99.reader.ui.tts

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where a generated sentence's measurement goes. */
internal fun interface TtsPreparationSpeedLog {
    fun record(voiceId: String?, sample: TtsPreparationSample)
}

/**
 * The device's own preparation speed, in one small file of its own. Deliberately not in any
 * chapter's `manifest.json`: that format is checked by the cloud archive and does not change.
 */
internal class TtsPreparationSpeedFile(private val file: File) : TtsPreparationSpeedLog {
    private var cached: TtsPreparationSpeedRecord? = null

    /** A missing or unreadable file is an empty record, so the fixed figures are used. */
    @Synchronized
    fun read(): TtsPreparationSpeedRecord = cached ?: decodePreparationSpeedRecord(
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull(),
    ).also { record -> cached = record }

    @Synchronized
    override fun record(voiceId: String?, sample: TtsPreparationSample) {
        val updated = read().with(voiceId, sample)
        cached = updated
        // An estimate is all this feeds: a write that fails costs nothing but the measurement.
        runCatching {
            val staging = File(file.parentFile, "${file.name}.part")
            staging.writeText(encodePreparationSpeedRecord(updated))
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
