package com.retro99.reader.ui.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderListeningStartTest {
    @Test
    fun `Play on an empty opening chapter reaches the controller exactly once before availability`() = runTest {
        var playbackRequests = 0
        val controller = object : com.retro99.reader.ui.navigator.TtsController by FakeTtsController(hasText = false) {
            override fun togglePlayback() { playbackRequests++ }
        }
        val setup = RecordedSetup()
        backgroundScope.launch {
            ReaderTtsSetup(
                controller, MutableStateFlow(locatorAt("cover.xhtml")),
                { ReaderTtsSetupSettings(null, false) }, { false }, setup.actions,
            ).run(keepNarrationActive = false)
        }
        runCurrent()
        assertFalse(setup.isReadAloudAvailable)
        var enabled = 0
        var entered = 0
        ReaderListeningStart({ enabled++ }, { entered++ }, controller::togglePlayback).start(
            ListenSource.DEVICE_VOICE, false, setup.isReadAloudAvailable,
            setup.voices.orEmpty().isNotEmpty(), false, false, true,
        )
        assertEquals(1, playbackRequests, "Play must reach the controller even before page availability")
        assertEquals(1, enabled)
        assertEquals(1, entered)
    }

    @Test
    fun `recorded narration keeps its playback and does not enable device voice`() {
        val calls = mutableListOf<String>()
        starter(calls).start(ListenSource.NARRATION, true, false, true, false, false, true)
        assertEquals(listOf("listen", "play"), calls)
    }

    @Test
    fun `a book not set up for read aloud still does nothing`() {
        val calls = mutableListOf<String>()
        starter(calls).start(ListenSource.DEVICE_VOICE, false, false, false, false, false, true)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `opening listening without autoplay or while already playing never toggles playback`() {
        val calls = mutableListOf<String>()
        starter(calls).start(ListenSource.DEVICE_VOICE, false, true, true, true, false, false)
        starter(calls).start(ListenSource.DEVICE_VOICE, false, true, true, true, true, true)
        assertEquals(listOf("listen", "listen"), calls)
    }

    @Test
    fun `device voice on a narration book does not change the saved enabled setting`() {
        val calls = mutableListOf<String>()
        starter(calls).start(ListenSource.DEVICE_VOICE, true, true, true, false, false, true)
        assertEquals(listOf("listen", "play"), calls)
    }

    private fun starter(calls: MutableList<String>) = ReaderListeningStart(
        { calls += "enable" }, { calls += "listen" }, { calls += "play" },
    )
}
