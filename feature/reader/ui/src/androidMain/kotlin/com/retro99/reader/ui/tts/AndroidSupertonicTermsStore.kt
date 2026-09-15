package com.retro99.reader.ui.tts

import android.content.Context

import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [SupertonicTermsStore::class])
class AndroidSupertonicTermsStore(
    @Provided context: Context,
) : SupertonicTermsStore {

    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun hasAcceptedCurrentTerms(): Boolean =
        preferences.getInt(KEY_ACCEPTED_TERMS_VERSION, 0) >=
                CURRENT_SUPERTONIC_TERMS_VERSION

    override fun acceptCurrentTerms() {
        preferences
            .edit()
            .putInt(KEY_ACCEPTED_TERMS_VERSION, CURRENT_SUPERTONIC_TERMS_VERSION)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "supertonic_terms"
        const val KEY_ACCEPTED_TERMS_VERSION = "accepted_terms_version"
    }
}
