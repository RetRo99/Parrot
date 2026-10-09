package com.retro99.catalogue.domain

/**
 * Where a catalogue download is. These are the stored states; "Getting ready" belongs to the
 * screen that is still loading a book's details and is never stored.
 */
sealed interface AcquisitionState {
    /** The value written to the database. */
    val key: String

    data object Waiting : AcquisitionState {
        override val key = "waiting"
    }

    data object Downloading : AcquisitionState {
        override val key = "downloading"
    }

    /** The file is on disk and is being checked: is it really an EPUB, is it protected. */
    data object Checking : AcquisitionState {
        override val key = "checking"
    }

    /** Checked, hashed and staged; being added to the library. */
    data object Adding : AcquisitionState {
        override val key = "adding"
    }

    data object Done : AcquisitionState {
        override val key = "done"
    }

    data class Failed(val reason: AcquisitionFailureReason) : AcquisitionState {
        override val key = KEY_FAILED
    }

    /** Parrot closed while this was running. It waits for "Start again". */
    data object Interrupted : AcquisitionState {
        override val key = "interrupted"
    }

    val failureReason: AcquisitionFailureReason? get() = (this as? Failed)?.reason

    /** Takes one of the download slots. */
    val isRunning: Boolean get() = this == Downloading || this == Checking || this == Adding

    val isFinished: Boolean get() = this == Done

    companion object {
        private const val KEY_FAILED = "failed"

        /** Null for anything that is not a stored state, so a bad row is never guessed at. */
        fun fromStorage(state: String, failureReason: String?): AcquisitionState? = when (state) {
            Waiting.key -> Waiting
            Downloading.key -> Downloading
            Checking.key -> Checking
            Adding.key -> Adding
            Done.key -> Done
            Interrupted.key -> Interrupted
            KEY_FAILED -> failureReason?.let(AcquisitionFailureReason::fromKey)?.let(::Failed)
            else -> null
        }
    }
}

/** Why a download did not finish. Each one has fixed wording and one action on screen. */
enum class AcquisitionFailureReason(val key: String) {
    /** The connection was lost, timed out, or the file arrived incomplete. Retry. */
    Connection("connection"),

    /** Larger than Parrot adds. Dismiss. */
    TooLarge("too_large"),

    /** Not enough space on this device. Retry. */
    Storage("storage"),

    /** Not a book Parrot can open. Dismiss. */
    Invalid("invalid"),

    /** Protected (DRM). Dismiss. */
    Protected("protected"),

    /** The catalogue did not allow the download. Retry. */
    Refused("refused"),

    /** The catalogue asks for account details. Sign in. */
    SignIn("sign_in"),
    ;

    companion object {
        fun fromKey(key: String): AcquisitionFailureReason? = entries.firstOrNull { reason -> reason.key == key }
    }
}
