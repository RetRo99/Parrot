package com.retro99.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retro99.reader.domain.linked.observedAtMillis
import com.retro99.reader.ui.model.PositionConflictUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.position_conflict_error
import com.retro99.books.ui.components.PositionConflictDialog as BooksPositionConflictDialog
import com.retro99.books.ui.components.positionOffer as booksPositionOffer

/**
 * Dialog for resolving position conflicts with the full position details. Used in the
 * reader when both sides are complete; each option card is the button (spec §3).
 */
@Composable
fun PositionConflictDialog(
    conflict: PositionConflictUiModel,
    onUseLocal: () -> Unit,
    onUseRemote: () -> Unit,
    modifier: Modifier = Modifier,
    serverName: String = "",
    /** What this device calls itself: "This phone", "This iPhone", "This tablet". */
    thisDeviceName: String = "",
    isResolving: Boolean = false,
    resolvingSide: com.retro99.books.ui.components.ConflictSide? = null,
    error: String? = null,
) {
    val candidates = conflict.candidates
    val localMillis = candidates.localPosition.observedAtMillis
    val remoteMillis = candidates.remotePosition.observedAtMillis
    // The newer position is listed first; strictly newer only, ties are peers.
    val remoteNewer = localMillis != null && remoteMillis != null && remoteMillis > localMillis
    val localNewer = localMillis != null && remoteMillis != null && localMillis > remoteMillis
    BooksPositionConflictDialog(
        localOffer = booksPositionOffer(
            position = candidates.localPosition,
            whereName = thisDeviceName,
            isLatest = localNewer,
            preferDeviceName = false,
        ),
        remoteOffer = booksPositionOffer(
            position = candidates.remotePosition,
            whereName = serverName,
            isLatest = remoteNewer,
        ),
        onUseLocal = onUseLocal,
        onUseRemote = onUseRemote,
        onDismissRequest = { /* Don't allow dismiss without choosing */ },
        isResolving = isResolving,
        resolvingSide = resolvingSide,
        error = error?.let { stringResource(StringRes.position_conflict_error) },
        modifier = modifier,
    )
}
