package com.retro99.settings.ui

import com.retro99.reader.domain.tts.preparedAudioSizeLabel

/** What the Settings "Prepared audio" row says and whether it can offer Delete all. */
internal data class PreparedAudioRowContent(val sizeLabel: String?, val canDeleteAll: Boolean)

internal fun preparedAudioRowContent(bytes: Long): PreparedAudioRowContent =
    if (bytes <= 0) {
        PreparedAudioRowContent(sizeLabel = null, canDeleteAll = false)
    } else {
        PreparedAudioRowContent(sizeLabel = preparedAudioSizeLabel(bytes), canDeleteAll = true)
    }
