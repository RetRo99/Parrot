package com.retro99.books.ui.components

import androidx.compose.runtime.Composable
import com.retro99.base.ui.compose.relativeTimeText
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.sync.domain.ObservedTime

/** Device and reading time, never the time the candidate was merely fetched or saved. */
@Composable
fun positionCandidateDetail(position: PositionDomainModel): String? = listOfNotNull(
    position.deviceName?.takeIf { it.isNotBlank() },
    ObservedTime.toEpochMillis(position.observedAt)?.let { relativeTimeText(it) },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")
