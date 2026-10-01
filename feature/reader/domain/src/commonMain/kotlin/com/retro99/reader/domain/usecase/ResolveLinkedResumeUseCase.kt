package com.retro99.reader.domain.usecase

import com.retro99.base.result.CompletableResult
import com.retro99.reader.domain.linked.LinkedResumeDismissals
import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.server.api.PositionOrigin
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** The person's answer to the resume prompt (§1.3, step 3). */
@Factory
class ResolveLinkedResumeUseCase(
    private val saveReadingProgressUseCase: SaveReadingProgressUseCase,
    @Provided private val dismissals: LinkedResumeDismissals,
) {
    /** "Continue": the offered place becomes the opened copy's own position, chosen now. */
    suspend fun continueFrom(offer: LinkedResumeOffer): CompletableResult =
        saveReadingProgressUseCase(
            offer.translated.position.copy(
                origin = PositionOrigin.User,
                observedAt = null,
            ),
        )

    /** "Stay here": this source reading isn't offered again. */
    suspend fun stayHere(offer: LinkedResumeOffer) {
        dismissals.dismiss(offer.dismissalEntry)
    }
}
