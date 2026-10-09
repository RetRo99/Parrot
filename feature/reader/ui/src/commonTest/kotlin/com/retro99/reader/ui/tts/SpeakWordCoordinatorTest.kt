package com.retro99.reader.ui.tts

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class SpeakWordCoordinatorTest {

    @Test
    fun `synthesizes first then pauses playing audio then plays then resumes`() = runTest {
        val events = mutableListOf<String>()
        val source = FakeWordAudioSource(events)
        val player = FakeWordPlayer(events)
        val playing = FakeInterruption(events, "narration", playing = true)
        val paused = FakeInterruption(events, "audiobook", playing = false)
        val coordinator = coordinator(source, player, listOf(playing, paused))

        coordinator.speak(word(text = "word"))
        advanceUntilIdle()

        assertEquals(listOf("synthesize", "pause:narration", "play", "stop", "resume:narration"), events)
        assertEquals(0, paused.pauses)
        assertEquals(0, paused.resumes)
        assertEquals(SpeakWordState.Idle, coordinator.state.value)
    }

    @Test
    fun `state walks Preparing with the voice name then Speaking then Idle`() = runTest {
        val source = FakeWordAudioSource().apply { gate = CompletableDeferred() }
        val player = FakeWordPlayer().apply { gate = CompletableDeferred() }
        val coordinator = coordinator(source, player, emptyList())

        coordinator.speak(word(text = "word", voiceName = "Supertonic F1"))
        runCurrent()
        assertEquals(SpeakWordState.Preparing("Supertonic F1"), coordinator.state.value)

        source.gate?.complete(Unit)
        runCurrent()
        assertEquals(SpeakWordState.Speaking, coordinator.state.value)

        player.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(SpeakWordState.Idle, coordinator.state.value)
    }

    @Test
    fun `synthesis failure reports once and pauses nothing`() = runTest {
        val events = mutableListOf<String>()
        val source = FakeWordAudioSource(events).apply { result = null }
        val player = FakeWordPlayer(events)
        val playing = FakeInterruption(events, "narration", playing = true)
        val coordinator = coordinator(source, player, listOf(playing))
        val failures = collectFailures(coordinator)

        coordinator.speak(word(text = "word", voiceId = "kokoro:0", isNeural = true))
        advanceUntilIdle()
        runCurrent()

        // "stop" is the finally's unconditional (no-op) player stop; nothing was paused.
        assertEquals(listOf("synthesize", "stop"), events)
        assertEquals(listOf(SpeakWordFailure(voiceId = "kokoro:0", isNeural = true)), failures)
        assertEquals(0, playing.pauses)
        assertEquals(SpeakWordState.Idle, coordinator.state.value)
    }

    @Test
    fun `stop while preparing cancels without touching the synthesizer stop`() = runTest {
        val events = mutableListOf<String>()
        val source = FakeWordAudioSource(events).apply { gate = CompletableDeferred() }
        val player = FakeWordPlayer(events)
        val coordinator = coordinator(source, player, emptyList())

        coordinator.speak(word(text = "word"))
        runCurrent()
        assertEquals(SpeakWordState.Preparing("Voice"), coordinator.state.value)

        coordinator.stop()
        advanceUntilIdle()

        assertEquals(SpeakWordState.Idle, coordinator.state.value)
        assertTrue(player.played.isEmpty())
        // The regression: the word path has its own cancel and never reaches the synthesizer's
        // global stop(), which would complete read-aloud's in-flight sentence as cancelled.
        assertEquals(0, source.globalStopCalls)
    }

    @Test
    fun `stop while speaking stops the player and resumes what was paused`() = runTest {
        val events = mutableListOf<String>()
        val source = FakeWordAudioSource(events)
        val player = FakeWordPlayer(events).apply { gate = CompletableDeferred() }
        val playing = FakeInterruption(events, "narration", playing = true)
        val coordinator = coordinator(source, player, listOf(playing))

        coordinator.speak(word(text = "word"))
        runCurrent()
        assertEquals(SpeakWordState.Speaking, coordinator.state.value)

        coordinator.stop()
        advanceUntilIdle()

        assertEquals(SpeakWordState.Idle, coordinator.state.value)
        assertTrue(player.stops >= 1)
        assertEquals(1, playing.resumes)
        assertEquals(0, source.globalStopCalls)
    }

    @Test
    fun `a second word replaces the first and never stacks playback`() = runTest {
        val events = mutableListOf<String>()
        val source = FakeWordAudioSource(events).apply { gate = CompletableDeferred() }
        val player = FakeWordPlayer(events)
        val coordinator = coordinator(source, player, emptyList())

        coordinator.speak(word(text = "first"))
        runCurrent()

        source.gate = null
        coordinator.speak(word(text = "second"))
        advanceUntilIdle()

        // The first request finishes its cleanup (stop) before the second synthesizes.
        assertEquals(listOf("synthesize", "stop", "synthesize", "play", "stop"), events)
        assertEquals(1, player.played.size)
        assertEquals(SpeakWordState.Idle, coordinator.state.value)
    }

    @Test
    fun `a word spoken right after stop waits for the stopped word's cleanup`() = runTest {
        val events = mutableListOf<String>()
        val firstClip = CompletableDeferred<Unit>()
        val secondSynthesis = CompletableDeferred<Unit>()
        val secondClip = CompletableDeferred<Unit>()
        val source = FakeWordAudioSource(events).apply {
            gatesByText = mapOf("second" to secondSynthesis)
        }
        val player = FakeWordPlayer(events).apply {
            gateQueue = ArrayDeque(listOf(firstClip, secondClip))
        }
        val narration = FakeInterruption(events, "narration", playing = true)
        val coordinator = coordinator(source, player, listOf(narration))

        coordinator.speak(word(text = "first"))
        runCurrent()
        assertEquals(SpeakWordState.Speaking, coordinator.state.value)
        // Collected from here, so the first entry is the first word's Speaking, still current.
        val states = collectStates(coordinator)

        // The dismissal's stop and the next word's tap, with no dispatch in between.
        coordinator.stop()
        coordinator.speak(word(text = "second"))
        advanceUntilIdle()
        secondSynthesis.complete(Unit)
        advanceUntilIdle()
        assertEquals(SpeakWordState.Speaking, coordinator.state.value)
        secondClip.complete(Unit)
        advanceUntilIdle()
        // The state collector runs in backgroundScope, which advanceUntilIdle does not dispatch.
        runCurrent()

        assertEquals(
            listOf(
                "synthesize", "pause:narration", "play", "stop", "resume:narration",
                "synthesize", "pause:narration", "play", "stop", "resume:narration",
            ),
            events,
        )
        assertEquals(2, player.played.size)
        assertEquals(
            listOf(SpeakWordState.Preparing("Voice"), SpeakWordState.Speaking, SpeakWordState.Idle),
            states.drop(1),
        )
    }

    @Test
    fun `a word spoken right after stop waits when the stopped synthesis unwinds late`() = runTest {
        val events = mutableListOf<String>()
        val firstSynthesis = CompletableDeferred<Unit>()
        val source = FakeWordAudioSource(events, dispatcher = StandardTestDispatcher(testScheduler)).apply {
            gatesByText = mapOf("first" to firstSynthesis)
        }
        val player = FakeWordPlayer(events)
        val narration = FakeInterruption(events, "narration", playing = true)
        val coordinator = coordinator(source, player, listOf(narration))

        coordinator.speak(word(text = "first"))
        advanceUntilIdle()
        assertEquals(SpeakWordState.Preparing("Voice"), coordinator.state.value)

        coordinator.stop()
        coordinator.speak(word(text = "second"))
        advanceUntilIdle()

        // The stopped word never played, so its cleanup must all land before the second word's.
        assertEquals(
            listOf(
                "synthesize", "stop",
                "synthesize", "pause:narration", "play", "stop", "resume:narration",
            ),
            events,
        )
        assertEquals(1, narration.pauses)
        assertEquals(1, narration.resumes)
        assertEquals(SpeakWordState.Idle, coordinator.state.value)
    }

    @Test
    fun `an unusable neural voice can never reach the download path`() = runTest {
        // The real resolver: the selected pack is not downloaded and terms are not accepted,
        // so it must hand back the system voice instead of the neural one.
        val notDownloaded = neuralVoice(id = "kokoro:0").copy(isDownloaded = false)
        val system = systemVoice(id = "en-us-local")
        val resolution = resolveWordVoice(
            selectedVoiceId = notDownloaded.id,
            voices = listOf(notDownloaded, system),
            defaultSystemVoiceId = system.id,
            hasAcceptedSupertonicTerms = false,
            preparingPackage = null,
            language = "en",
        )
        assertTrue(resolution is WordVoiceResolution.Usable)
        // The fake source throws if asked to synthesize an unusable voice: reaching the
        // synthesizer with it is what triggers ensureLoaded -> installVersion (~150 MB).
        val source = FakeWordAudioSource(unusableVoiceIds = setOf(notDownloaded.id))
        val coordinator = coordinator(source, FakeWordPlayer(), emptyList())

        coordinator.speak(
            word(
                text = "word",
                voiceId = resolution.voiceId,
                isNeural = resolution.isNeural,
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(system.id), source.requestedVoiceIds)
    }

    @Test
    fun `hidden resolution is never spoken`() = runTest {
        val source = FakeWordAudioSource(unusableVoiceIds = setOf("kokoro:0"))
        val coordinator = coordinator(source, FakeWordPlayer(), emptyList())

        val resolution = resolveWordVoice(
            selectedVoiceId = "kokoro:0",
            voices = listOf(neuralVoice(id = "kokoro:0").copy(isDownloaded = false)),
            defaultSystemVoiceId = null,
            hasAcceptedSupertonicTerms = false,
            preparingPackage = null,
            language = "en",
        )
        if (resolution is WordVoiceResolution.Usable) {
            coordinator.speak(word(voiceId = resolution.voiceId, isNeural = resolution.isNeural))
        }
        advanceUntilIdle()

        assertTrue(source.requestedVoiceIds.isEmpty())
        assertEquals(SpeakWordState.Idle, coordinator.state.value)
    }

    /**
     * The coordinator runs in the test scope, not [TestScope.backgroundScope]: since
     * coroutines-test 1.11, `advanceUntilIdle` stops once only background work remains, so
     * background-launched jobs would never be dispatched by it.
     */
    private fun TestScope.coordinator(
        source: WordAudioSource,
        player: WordPlayer,
        interruptions: List<WordAudioInterruption>,
    ) = SpeakWordCoordinator(
        scope = this,
        audioSource = source,
        player = player,
        interruptions = interruptions,
    )

    private fun TestScope.collectFailures(coordinator: SpeakWordCoordinator): List<SpeakWordFailure> {
        val failures = mutableListOf<SpeakWordFailure>()
        backgroundScope.launch {
            coordinator.failures.collect { failure -> failures += failure }
        }
        runCurrent()
        return failures
    }

    /** A conflating collector: it records the states a UI subscriber would actually see. */
    private fun TestScope.collectStates(coordinator: SpeakWordCoordinator): List<SpeakWordState> {
        val states = mutableListOf<SpeakWordState>()
        backgroundScope.launch {
            coordinator.state.collect { state -> states += state }
        }
        runCurrent()
        return states
    }

    private fun word(
        text: String = "word",
        voiceId: String = "en-us-local",
        voiceName: String = "Voice",
        isNeural: Boolean = false,
        rate: Float = 1f,
    ) = ResolvedWord(
        text = text,
        voiceId = voiceId,
        voiceName = voiceName,
        isNeural = isNeural,
        rate = rate,
    )

    private fun systemVoice(id: String) = TtsVoice(
        id = id,
        name = "English (US) local",
        locale = "en-US",
        quality = 400,
        latency = 100,
    )

    private fun neuralVoice(id: String) = TtsVoice(
        id = id,
        name = "Kokoro Amy",
        locale = "en-US",
        isNeural = true,
        isDownloaded = true,
    )
}

private class FakeClip : WordAudioClip

private class FakeWordAudioSource(
    private val events: MutableList<String>? = null,
    private val unusableVoiceIds: Set<String> = emptySet(),
    /**
     * Models the real [TtsWordAudioSource], which synthesizes inside
     * `withContext(Dispatchers.IO)`: cancelling a synthesis in flight then needs more than one
     * dispatch to unwind, so the cancelled request's cleanup lands later than the cancel call.
     */
    private val dispatcher: CoroutineContext = EmptyCoroutineContext,
) : WordAudioSource {

    var result: WordAudioClip? = FakeClip()
    var gate: CompletableDeferred<Unit>? = null

    /** Per-word gates, for a test that has to hold two requests at different points. */
    var gatesByText: Map<String, CompletableDeferred<Unit>> = emptyMap()

    /** Simulates the synthesizer's global stop(); the word path must never reach it. */
    var globalStopCalls: Int = 0
        private set

    val requestedVoiceIds = mutableListOf<String>()

    override suspend fun synthesize(text: String, voiceId: String, rate: Float): WordAudioClip? {
        check(voiceId !in unusableVoiceIds) {
            "installVersion reached: unusable voice $voiceId was handed to the synthesizer"
        }
        events?.add("synthesize")
        requestedVoiceIds += voiceId
        return withContext(dispatcher) {
            (gatesByText[text] ?: gate)?.await()
            result
        }
    }
}

private class FakeWordPlayer(private val events: MutableList<String>? = null) : WordPlayer {

    var gate: CompletableDeferred<Unit>? = null

    /** One gate per play call, for a test that holds two clips at once; null entries never gate. */
    var gateQueue: ArrayDeque<CompletableDeferred<Unit>?>? = null
    val played = mutableListOf<WordAudioClip>()
    var stops: Int = 0
        private set

    override suspend fun play(clip: WordAudioClip) {
        events?.add("play")
        played += clip
        val queued = gateQueue
        if (queued != null) queued.removeFirstOrNull()?.await() else gate?.await()
    }

    override fun stop() {
        events?.add("stop")
        stops++
    }
}

private class FakeInterruption(
    private val events: MutableList<String>? = null,
    private val name: String,
    private var playing: Boolean,
) : WordAudioInterruption {

    var pauses: Int = 0
        private set
    var resumes: Int = 0
        private set

    override fun isPlayingNow(): Boolean = playing

    override fun pause() {
        events?.add("pause:$name")
        pauses++
        playing = false
    }

    override fun resume() {
        events?.add("resume:$name")
        resumes++
        playing = true
    }
}
