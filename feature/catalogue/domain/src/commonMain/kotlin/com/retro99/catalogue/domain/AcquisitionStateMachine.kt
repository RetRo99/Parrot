package com.retro99.catalogue.domain

import com.retro99.catalogue.domain.AcquisitionFailureReason.Connection
import com.retro99.catalogue.domain.AcquisitionFailureReason.Invalid
import com.retro99.catalogue.domain.AcquisitionFailureReason.Protected
import com.retro99.catalogue.domain.AcquisitionFailureReason.Refused
import com.retro99.catalogue.domain.AcquisitionFailureReason.SignIn
import com.retro99.catalogue.domain.AcquisitionFailureReason.Storage
import com.retro99.catalogue.domain.AcquisitionFailureReason.TooLarge
import com.retro99.catalogue.domain.AcquisitionState.Adding
import com.retro99.catalogue.domain.AcquisitionState.Checking
import com.retro99.catalogue.domain.AcquisitionState.Done
import com.retro99.catalogue.domain.AcquisitionState.Downloading
import com.retro99.catalogue.domain.AcquisitionState.Failed
import com.retro99.catalogue.domain.AcquisitionState.Interrupted
import com.retro99.catalogue.domain.AcquisitionState.Waiting

sealed interface AcquisitionEvent {
    /** A download slot is free and this request is next in order. */
    data object Start : AcquisitionEvent

    /** Every byte arrived and matches what the catalogue declared. */
    data object DownloadFinished : AcquisitionEvent

    /** The file is a readable, unprotected EPUB and has been hashed. */
    data object CheckPassed : AcquisitionEvent

    /** The book is in the library. */
    data object Finished : AcquisitionEvent

    data class Fail(val reason: AcquisitionFailureReason) : AcquisitionEvent

    data object Retry : AcquisitionEvent

    /** Account details were saved for the catalogue that asked for them. */
    data object SignedIn : AcquisitionEvent

    data object Dismiss : AcquisitionEvent

    data object StartAgain : AcquisitionEvent

    data object Cancel : AcquisitionEvent

    /** Parrot started; whatever was running when it closed is no longer running. */
    data object AppRestarted : AcquisitionEvent
}

sealed interface AcquisitionTransition {
    data class To(val state: AcquisitionState) : AcquisitionTransition

    /** The request is deleted. */
    data object Removed : AcquisitionTransition

    /** Not a legal move; nothing changes. */
    data object Rejected : AcquisitionTransition
}

/** The legal moves of a catalogue download. Pure: no clock, no storage, no network. */
object AcquisitionStateMachine {
    private val downloadFailures = setOf(Connection, TooLarge, Storage, Refused, SignIn)
    private val checkFailures = setOf(Invalid, Protected, Storage)
    private val addFailures = setOf(Invalid, Storage)
    private val retryable = setOf(Connection, Storage, Refused)
    private val dismissible = setOf(TooLarge, Invalid, Protected)

    fun transition(state: AcquisitionState, event: AcquisitionEvent): AcquisitionTransition = when (event) {
        AcquisitionEvent.Start -> move(state == Waiting, Downloading)
        AcquisitionEvent.DownloadFinished -> move(state == Downloading, Checking)
        AcquisitionEvent.CheckPassed -> move(state == Checking, Adding)
        AcquisitionEvent.Finished -> move(state == Adding, Done)
        is AcquisitionEvent.Fail -> {
            val allowed = when (state) {
                Downloading -> downloadFailures
                Checking -> checkFailures
                Adding -> addFailures
                else -> emptySet()
            }
            move(event.reason in allowed, Failed(event.reason))
        }
        AcquisitionEvent.Retry -> move(state is Failed && state.reason in retryable, Waiting)
        AcquisitionEvent.SignedIn -> move(state == Failed(SignIn), Waiting)
        AcquisitionEvent.StartAgain -> move(state == Interrupted, Waiting)
        AcquisitionEvent.Dismiss ->
            if (state is Failed && state.reason in dismissible) AcquisitionTransition.Removed else AcquisitionTransition.Rejected
        AcquisitionEvent.Cancel ->
            if (state.isFinished) AcquisitionTransition.Rejected else AcquisitionTransition.Removed
        AcquisitionEvent.AppRestarted -> AcquisitionTransition.To(if (state.isRunning) Interrupted else state)
    }

    private fun move(legal: Boolean, target: AcquisitionState): AcquisitionTransition =
        if (legal) AcquisitionTransition.To(target) else AcquisitionTransition.Rejected
}
