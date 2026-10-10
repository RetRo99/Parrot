package com.retro99.reader.ui.reader

import com.retro99.reader.ui.navigator.TtsController
import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsVoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

/** The settings read-aloud setup needs, so it never sees a domain model. */
internal data class ReaderTtsSetupSettings(
    val voiceId: String?,
    val isTtsEnabled: Boolean,
)

/** Everything the setup hands back to the reader, in the order it is decided. */
internal class ReaderTtsSetupActions(
    /** Read-aloud becomes the controller the play button drives. */
    val takeOverNarration: () -> Unit,
    val showVoices: (voices: List<TtsVoice>, selectedVoiceId: String?) -> Unit,
    /** Starts the read-aloud collectors; called once per reader. */
    val startCollectors: () -> Unit,
    val markReadAloudAvailable: () -> Unit,
    val saveSelectedVoice: suspend (readVoiceId: String?, selectedVoiceId: String?) -> Unit,
    val enableSentencePlayback: () -> Unit,
    val prepareVoice: (voiceId: String) -> Unit,
)

/**
 * The one read-aloud setup a reader screen does: which voices it offers, which one is
 * selected, and when read-aloud counts as available.
 *
 * Separate from [ReaderViewModel] so it can be tested: the ViewModel takes more than forty
 * dependencies, while this takes the TTS controller, the locator flow, the settings read and
 * the callbacks that move the state.
 */
internal class ReaderTtsSetup(
    private val ttsController: TtsController,
    private val locators: Flow<*>,
    private val readSettings: suspend () -> ReaderTtsSetupSettings,
    private val hasAcceptedSupertonicTerms: () -> Boolean,
    private val actions: ReaderTtsSetupActions,
) {

    /**
     * @param keepNarrationActive a book with recorded narration keeps it; the device voice is
     * offered alongside and must not take the play button over.
     */
    suspend fun run(keepNarrationActive: Boolean) {
        locators.first()

        val settings = readSettings()
        val voices = ttsController.availableVoices()
        val selectedVoiceId = selectVoiceId(voices, settings.voiceId)

        // Which voices this book can be read in does not depend on the page it opens on, and
        // neither does whether a play press reaches read-aloud (TTS-F23). The collectors are
        // started here, once, so availability turning on later never starts them again.
        if (!keepNarrationActive) actions.takeOverNarration()
        actions.showVoices(voices, selectedVoiceId)
        actions.startCollectors()

        ttsController.selectVoice(selectedVoiceId)
        if (settings.voiceId != selectedVoiceId) {
            actions.saveSelectedVoice(settings.voiceId, selectedVoiceId)
        }

        // Availability is the one answer the page does decide: a book with nothing readable
        // anywhere never claims it. Most Gutenberg books open on a cover or a title page, so
        // the wait is for the first chapter with text, however far in that is.
        if (!awaitReadableContent()) return
        actions.markReadAloudAvailable()

        if (settings.isTtsEnabled && !keepNarrationActive) actions.enableSentencePlayback()

        val selectedVoice = voices.firstOrNull { voice -> voice.id == selectedVoiceId }
        if (
            settings.isTtsEnabled &&
            selectedVoice?.isNeural == true &&
            !selectedVoice.needsDownload
        ) {
            actions.prepareVoice(selectedVoice.id)
        }
    }

    /**
     * True once the reader is on a chapter with text. The check is per chapter — it reads the
     * loaded page — so it is asked again on every locator move until one answers yes.
     */
    private suspend fun awaitReadableContent(): Boolean {
        if (ttsController.hasReadableContent()) return true
        return locators.firstOrNull { ttsController.hasReadableContent() } != null
    }

    /** The saved voice, unless it is gone or its terms have not been accepted. */
    private fun selectVoiceId(voices: List<TtsVoice>, savedVoiceId: String?): String? {
        val savedVoice = voices.firstOrNull { voice -> voice.id == savedVoiceId }
        return savedVoiceId?.takeIf {
            savedVoice != null &&
                    (
                            savedVoice.neuralVoicePackage != NeuralVoicePackage.SUPERTONIC ||
                                    hasAcceptedSupertonicTerms()
                            )
        }
    }
}
