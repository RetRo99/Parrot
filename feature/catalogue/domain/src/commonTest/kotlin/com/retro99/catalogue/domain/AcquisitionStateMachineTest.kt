package com.retro99.catalogue.domain

import com.retro99.catalogue.domain.AcquisitionEvent.AppRestarted
import com.retro99.catalogue.domain.AcquisitionEvent.Cancel
import com.retro99.catalogue.domain.AcquisitionEvent.CheckPassed
import com.retro99.catalogue.domain.AcquisitionEvent.Dismiss
import com.retro99.catalogue.domain.AcquisitionEvent.DownloadFinished
import com.retro99.catalogue.domain.AcquisitionEvent.Fail
import com.retro99.catalogue.domain.AcquisitionEvent.Finished
import com.retro99.catalogue.domain.AcquisitionEvent.Retry
import com.retro99.catalogue.domain.AcquisitionEvent.SignedIn
import com.retro99.catalogue.domain.AcquisitionEvent.Start
import com.retro99.catalogue.domain.AcquisitionEvent.StartAgain
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
import com.retro99.catalogue.domain.AcquisitionTransition.Rejected
import com.retro99.catalogue.domain.AcquisitionTransition.Removed
import com.retro99.catalogue.domain.AcquisitionTransition.To
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcquisitionStateMachineTest {

    private val allStates: List<AcquisitionState> =
        listOf(Waiting, Downloading, Checking, Adding, Done, Interrupted) +
            AcquisitionFailureReason.entries.map { reason -> Failed(reason) }

    private val allEvents: List<AcquisitionEvent> =
        listOf(Start, DownloadFinished, CheckPassed, Finished, Retry, SignedIn, Dismiss, StartAgain, Cancel, AppRestarted) +
            AcquisitionFailureReason.entries.map { reason -> Fail(reason) }

    /** Every legal move. A pair that is not listed here must be rejected. */
    private val legal: Map<Pair<AcquisitionState, AcquisitionEvent>, AcquisitionTransition> = buildMap {
        // The normal path.
        put(Waiting to Start, To(Downloading))
        put(Downloading to DownloadFinished, To(Checking))
        put(Checking to CheckPassed, To(Adding))
        put(Adding to Finished, To(Done))

        // What can go wrong, and where.
        listOf(Connection, TooLarge, Storage, Refused, SignIn).forEach { reason ->
            put(Downloading to Fail(reason), To(Failed(reason)))
        }
        listOf(Invalid, Protected, Storage).forEach { reason ->
            put(Checking to Fail(reason), To(Failed(reason)))
        }
        listOf(Invalid, Storage).forEach { reason ->
            put(Adding to Fail(reason), To(Failed(reason)))
        }

        // What you can do about a failure.
        listOf(Connection, Storage, Refused).forEach { reason -> put(Failed(reason) to Retry, To(Waiting)) }
        listOf(TooLarge, Invalid, Protected).forEach { reason -> put(Failed(reason) to Dismiss, Removed) }
        put(Failed(SignIn) to SignedIn, To(Waiting))
        put(Interrupted to StartAgain, To(Waiting))

        // Cancel deletes the request from every state that is not finished.
        allStates.filter { state -> state != Done }.forEach { state -> put(state to Cancel, Removed) }

        // Closing Parrot stops whatever was running; everything else keeps its state.
        listOf(Downloading, Checking, Adding).forEach { state -> put(state to AppRestarted, To(Interrupted)) }
        allStates.filter { state -> state !in listOf(Downloading, Checking, Adding) }
            .forEach { state -> put(state to AppRestarted, To(state)) }
    }

    @Test
    fun `every state and event pair follows the transition table`() {
        allStates.forEach { state ->
            allEvents.forEach { event ->
                // When
                val transition = AcquisitionStateMachine.transition(state, event)

                // Then
                assertEquals(legal[state to event] ?: Rejected, transition, "$state on $event")
            }
        }
    }

    @Test
    fun `the table covers seven failure reasons and nothing else`() {
        assertEquals(
            listOf("connection", "too_large", "storage", "invalid", "protected", "refused", "sign_in"),
            AcquisitionFailureReason.entries.map { reason -> reason.key },
        )
    }

    @Test
    fun `cancel removes the request from every state that is not finished`() {
        allStates.forEach { state ->
            val expected = if (state == Done) Rejected else Removed
            assertEquals(expected, AcquisitionStateMachine.transition(state, Cancel), "$state")
        }
    }

    @Test
    fun `retry is offered for connection storage and refused only`() {
        AcquisitionFailureReason.entries.forEach { reason ->
            val retried = AcquisitionStateMachine.transition(Failed(reason), Retry)
            if (reason in setOf(Connection, Storage, Refused)) {
                assertEquals(To(Waiting), retried, "$reason")
            } else {
                assertEquals(Rejected, retried, "$reason")
            }
        }
    }

    @Test
    fun `dismiss is offered for too large invalid and protected only`() {
        AcquisitionFailureReason.entries.forEach { reason ->
            val dismissed = AcquisitionStateMachine.transition(Failed(reason), Dismiss)
            if (reason in setOf(TooLarge, Invalid, Protected)) {
                assertEquals(Removed, dismissed, "$reason")
            } else {
                assertEquals(Rejected, dismissed, "$reason")
            }
        }
    }

    @Test
    fun `an interrupted download starts again from waiting and never by itself`() {
        assertEquals(To(Waiting), AcquisitionStateMachine.transition(Interrupted, StartAgain))
        assertEquals(Rejected, AcquisitionStateMachine.transition(Interrupted, Start))
        assertEquals(To(Interrupted), AcquisitionStateMachine.transition(Interrupted, AppRestarted))
    }

    @Test
    fun `a restart interrupts running work and leaves waiting requests waiting`() {
        assertEquals(To(Interrupted), AcquisitionStateMachine.transition(Downloading, AppRestarted))
        assertEquals(To(Interrupted), AcquisitionStateMachine.transition(Checking, AppRestarted))
        assertEquals(To(Interrupted), AcquisitionStateMachine.transition(Adding, AppRestarted))
        assertEquals(To(Waiting), AcquisitionStateMachine.transition(Waiting, AppRestarted))
        assertEquals(To(Done), AcquisitionStateMachine.transition(Done, AppRestarted))
    }

    @Test
    fun `done is the only finished state and nothing leaves it`() {
        assertTrue(Done.isFinished)
        allStates.filter { state -> state != Done }.forEach { state -> assertFalse(state.isFinished, "$state") }
        allEvents.filter { event -> event != AppRestarted }.forEach { event ->
            assertEquals(Rejected, AcquisitionStateMachine.transition(Done, event), "$event")
        }
    }

    @Test
    fun `only downloading checking and adding hold one of the two slots`() {
        assertEquals(
            listOf(Downloading, Checking, Adding),
            allStates.filter { state -> state.isRunning },
        )
    }

    @Test
    fun `every state survives being stored and read back`() {
        allStates.forEach { state ->
            val restored = AcquisitionState.fromStorage(state.key, state.failureReason?.key)
            assertEquals(state, restored)
        }
        assertEquals(
            listOf("waiting", "downloading", "checking", "adding", "done", "interrupted", "failed"),
            allStates.map { state -> state.key }.distinct(),
        )
    }

    @Test
    fun `getting ready and other unknown values are not stored states`() {
        assertNull(AcquisitionState.fromStorage("getting_ready", null))
        assertNull(AcquisitionState.fromStorage("failed", null))
        assertNull(AcquisitionState.fromStorage("failed", "cancelled"))
        assertNull(AcquisitionFailureReason.fromKey("interrupted"))
    }
}
