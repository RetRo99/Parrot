package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.TtsChapterPreparationFailure
import com.retro99.reader.ui.tts.TtsChapterPreparationRequest
import com.retro99.reader.ui.tts.TtsChapterPreparationState
import com.retro99.reader.ui.tts.TtsPreparedChapterAudio
import com.retro99.reader.ui.tts.TtsVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreparedChapterRowStateTest {
    private val chapter = "chapter-3.xhtml"

    private fun row(
        audio: TtsPreparedChapterAudio = TtsPreparedChapterAudio.NotPrepared,
        preparation: TtsChapterPreparationState = TtsChapterPreparationState.Idle,
        voice: PreparedChapterVoice = PreparedChapterVoice.USABLE,
        isReadAloudAvailable: Boolean = true,
        isNarrationSelected: Boolean = false,
        chapterHref: String? = chapter,
    ) = derivePreparedChapterRow(
        isReadAloudAvailable = isReadAloudAvailable,
        isNarrationSelected = isNarrationSelected,
        chapterHref = chapterHref,
        audio = audio,
        preparation = preparation,
        voice = voice,
    )

    @Test fun `a chapter with nothing prepared offers preparation`() {
        assertEquals(PreparedChapterRowState.NotPrepared, row())
    }

    @Test fun `a chapter that has begun but prepared no sentence still reads as not prepared`() {
        assertEquals(PreparedChapterRowState.NotPrepared, row(audio = TtsPreparedChapterAudio.Partial(0, 151)))
    }

    @Test fun `this chapter preparing shows its own counts`() {
        assertEquals(
            PreparedChapterRowState.Preparing(42, 151),
            row(preparation = TtsChapterPreparationState.Running(chapter, 42, 151)),
        )
    }

    @Test fun `another chapter preparing says so and offers nothing`() {
        assertEquals(
            PreparedChapterRowState.PreparingAnotherChapter,
            row(preparation = TtsChapterPreparationState.Running("chapter-9.xhtml", 1, 60)),
        )
    }

    @Test fun `a prepared chapter shows its size`() {
        assertEquals(
            PreparedChapterRowState.Ready(2_400_000),
            row(audio = TtsPreparedChapterAudio.Ready(2_400_000)),
        )
    }

    @Test fun `a partly prepared chapter shows how far it got`() {
        assertEquals(
            PreparedChapterRowState.Partly(42, 151),
            row(audio = TtsPreparedChapterAudio.Partial(42, 151)),
        )
    }

    @Test fun `audio made for another voice or speed names the one it was made for`() {
        assertEquals(
            PreparedChapterRowState.OtherSettings("kokoro:heart", 1.25f),
            row(
                audio = TtsPreparedChapterAudio.OtherSettings(
                    voiceId = "kokoro:heart", rate = 1.25f, pitch = 1f,
                    done = 151, total = 151, complete = true,
                ),
            ),
        )
    }

    @Test fun `a failed preparation of this chapter shows the failure over what it kept`() {
        assertEquals(
            PreparedChapterRowState.Failed(TtsChapterPreparationFailure.SENTENCE_FAILED),
            row(
                audio = TtsPreparedChapterAudio.Partial(42, 151),
                preparation = TtsChapterPreparationState.Failed(chapter, TtsChapterPreparationFailure.SENTENCE_FAILED),
            ),
        )
    }

    @Test fun `a failure in another chapter does not show here`() {
        assertEquals(
            PreparedChapterRowState.NotPrepared,
            row(preparation = TtsChapterPreparationState.Failed("chapter-9.xhtml", TtsChapterPreparationFailure.SENTENCE_FAILED)),
        )
    }

    @Test fun `a cancelled preparation leaves the partly prepared row`() {
        assertEquals(
            PreparedChapterRowState.Partly(7, 60),
            row(
                audio = TtsPreparedChapterAudio.Partial(7, 60),
                preparation = TtsChapterPreparationState.Cancelled(chapter),
            ),
        )
    }

    @Test fun `a completed preparation shows the ready row`() {
        assertEquals(
            PreparedChapterRowState.Ready(1_000),
            row(
                audio = TtsPreparedChapterAudio.Ready(1_000),
                preparation = TtsChapterPreparationState.Completed(chapter),
            ),
        )
    }

    @Test fun `a voice that cannot be used leads to the voices sheet instead`() {
        assertEquals(
            PreparedChapterRowState.VoiceUnusable(needsTerms = false),
            row(voice = PreparedChapterVoice.PACK_MISSING),
        )
        assertEquals(
            PreparedChapterRowState.VoiceUnusable(needsTerms = true),
            row(voice = PreparedChapterVoice.TERMS_REQUIRED),
        )
    }

    @Test fun `progress of the chapter on screen wins over an unusable voice`() {
        // The pack can be deleted while this chapter is being prepared; the row keeps
        // showing the work and its Cancel rather than hiding it behind a voice warning.
        assertEquals(
            PreparedChapterRowState.Preparing(3, 60),
            row(
                preparation = TtsChapterPreparationState.Running(chapter, 3, 60),
                voice = PreparedChapterVoice.PACK_MISSING,
            ),
        )
    }

    @Test fun `the row is not shown for recorded narration or without read aloud or chapter`() {
        assertNull(row(isNarrationSelected = true))
        assertNull(row(isReadAloudAvailable = false))
        assertNull(row(chapterHref = null))
    }

    @Test fun `voice usability follows the read aloud gates`() {
        assertEquals(PreparedChapterVoice.USABLE, preparedChapterVoice(null, hasAcceptedTerms = false))
        assertEquals(
            PreparedChapterVoice.USABLE,
            preparedChapterVoice(voice("en-us-x-sfg", isNeural = false), hasAcceptedTerms = false),
        )
        assertEquals(
            PreparedChapterVoice.PACK_MISSING,
            preparedChapterVoice(voice("kokoro:heart", isDownloaded = false), hasAcceptedTerms = true),
        )
        assertEquals(
            PreparedChapterVoice.USABLE,
            preparedChapterVoice(voice("kokoro:heart"), hasAcceptedTerms = false),
        )
        assertEquals(
            PreparedChapterVoice.TERMS_REQUIRED,
            preparedChapterVoice(voice("supertonic:one"), hasAcceptedTerms = false),
        )
        assertEquals(
            PreparedChapterVoice.USABLE,
            preparedChapterVoice(voice("supertonic:one"), hasAcceptedTerms = true),
        )
    }

    @Test fun `what a press does beyond the row`() {
        assertEquals(
            PreparedChapterPressOutcome.OPEN_VOICES,
            preparedChapterPressOutcome(TtsChapterPreparationRequest.VOICE_UNUSABLE),
        )
        assertEquals(
            PreparedChapterPressOutcome.SHOW_FAILURE,
            preparedChapterPressOutcome(TtsChapterPreparationRequest.NOTIFICATIONS_DENIED),
        )
        assertEquals(
            PreparedChapterPressOutcome.SHOW_FAILURE,
            preparedChapterPressOutcome(TtsChapterPreparationRequest.UNAVAILABLE),
        )
        for (quiet in listOf(
            TtsChapterPreparationRequest.STARTED,
            TtsChapterPreparationRequest.ALREADY_PREPARING,
            TtsChapterPreparationRequest.NOT_ENOUGH_SPACE,
        )) {
            assertEquals(PreparedChapterPressOutcome.NONE, preparedChapterPressOutcome(quiet), quiet.name)
        }
    }

    private fun voice(id: String, isNeural: Boolean = true, isDownloaded: Boolean = true) =
        TtsVoice(id = id, name = id, locale = "en-US", isNeural = isNeural, isDownloaded = isDownloaded)
}
