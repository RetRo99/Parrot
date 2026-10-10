package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.TtsChapterPreparationState
import com.retro99.reader.ui.tts.TtsPreparedChapterAudio
import com.retro99.translations.StringRes
import resources.translations.reader_tts_prepared_chapter_cancelling
import kotlin.test.Test
import kotlin.test.assertEquals

/** Cancel answers at once: what the row shows between the press and the job really stopping. */
class PreparedChapterCancellingTest {

    private fun row(chapterHref: String, preparation: TtsChapterPreparationState) = derivePreparedChapterRow(
        isReadAloudAvailable = true,
        isNarrationSelected = false,
        chapterHref = chapterHref,
        audio = TtsPreparedChapterAudio.Partial(42, 369),
        preparation = preparation,
        voice = PreparedChapterVoice.USABLE,
    )

    @Test fun `a job that is cancelling shows cancelling for its chapter`() {
        val cancelling = TtsChapterPreparationState.Running("c1.xhtml", 42, 369, remainingMs = 600_000, isCancelling = true)
        assertEquals(PreparedChapterRowState.Cancelling(42, 369), row("c1.xhtml", cancelling))
    }

    @Test fun `another chapter still reads as busy while that job is cancelling`() {
        val cancelling = TtsChapterPreparationState.Running("c1.xhtml", 42, 369, isCancelling = true)
        assertEquals(PreparedChapterRowState.PreparingAnotherChapter, row("c2.xhtml", cancelling))
    }

    @Test fun `once the job has stopped the row is the partly prepared chapter again`() {
        assertEquals(
            PreparedChapterRowState.Partly(42, 369),
            row("c1.xhtml", TtsChapterPreparationState.Cancelled("c1.xhtml")),
        )
    }

    @Test fun `cancelling says so keeps the bar and shows a cancel button that cannot be pressed`() {
        val ui = preparedChapterRowUi(PreparedChapterRowState.Cancelling(42, 369))
        assertEquals(StringRes.reader_tts_prepared_chapter_cancelling, ui.status)
        assertEquals(emptyList(), ui.statusArgs)
        assertEquals(42f / 369f, ui.progress)
        assertEquals(listOf(PreparedChapterAction.CANCEL), ui.actions)
        assertEquals(setOf(PreparedChapterAction.CANCEL), ui.disabled)
    }

    @Test fun `while preparing the cancel button can be pressed`() {
        val ui = preparedChapterRowUi(PreparedChapterRowState.Preparing(42, 369))
        assertEquals(listOf(PreparedChapterAction.CANCEL), ui.actions)
        assertEquals(emptySet(), ui.disabled)
    }
}
