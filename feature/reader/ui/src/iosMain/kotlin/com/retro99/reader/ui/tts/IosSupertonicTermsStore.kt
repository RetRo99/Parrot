package com.retro99.reader.ui.tts

import org.koin.core.annotation.Single

import platform.Foundation.NSUserDefaults

@Single(binds = [SupertonicTermsStore::class])
class IosSupertonicTermsStore : SupertonicTermsStore {

    override fun hasAcceptedCurrentTerms(): Boolean =
        NSUserDefaults.standardUserDefaults.integerForKey(KEY_ACCEPTED_TERMS_VERSION) >=
                CURRENT_SUPERTONIC_TERMS_VERSION

    override fun acceptCurrentTerms() {
        NSUserDefaults.standardUserDefaults.setInteger(
            value = CURRENT_SUPERTONIC_TERMS_VERSION.toLong(),
            forKey = KEY_ACCEPTED_TERMS_VERSION,
        )
    }

    private companion object {
        const val KEY_ACCEPTED_TERMS_VERSION = "supertonic_accepted_terms_version"
    }
}
