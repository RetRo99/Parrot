package com.retro99.reader.ui.tts

const val CURRENT_SUPERTONIC_TERMS_VERSION = 1

interface SupertonicTermsStore {

    fun hasAcceptedCurrentTerms(): Boolean

    fun acceptCurrentTerms()
}
