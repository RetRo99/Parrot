package com.retro99.books.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.position_conflict_latest
import resources.translations.position_conflict_other_replaced
import resources.translations.position_conflict_title

/** Which of the two conflict positions an action addresses. */
enum class ConflictSide { Local, Remote }

/**
 * "Where do you want to continue?" (spec §3): two full-width option cards, stacked
 * vertically, the newer one first with a 2dp accent border and a "Latest" pill. Each
 * card is the button — tapping it applies that position and closes the dialog. Under
 * the cards one quiet note; on failure one error line and the offer to try again.
 * No dialog action buttons; Back and outside taps stay blocked by [blockDismiss].
 */
@Composable
fun PositionConflictDialog(
    localOffer: PositionOffer,
    remoteOffer: PositionOffer,
    onUseLocal: () -> Unit,
    onUseRemote: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    isResolving: Boolean = false,
    /** The side being applied right now; its card shows the progress. */
    resolvingSide: ConflictSide? = null,
    /** A message for the failed last attempt; null while nothing failed. */
    error: String? = null,
) {
    EmberDialog(
        onDismissRequest = onDismissRequest,
        title = stringResource(StringRes.position_conflict_title),
        actions = emptyList(),
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sortedOffers(localOffer, remoteOffer).forEach { (offer, side) ->
                PositionOfferCard(
                    offer = offer,
                    selected = isResolving && resolvingSide == side,
                    enabled = !isResolving,
                    onClick = onClickFor(side, onUseLocal, onUseRemote),
                )
            }
            Text(
                text = stringResource(StringRes.position_conflict_other_replaced),
                style = Ember.type.meta.copy(fontSize = 13.sp),
                color = Ember.colors.ink2,
            )
            error?.let { message ->
                Text(
                    text = message,
                    style = Ember.type.meta.copy(fontSize = 13.sp),
                    color = Ember.colors.error,
                )
            }
        }
    }
}

private fun onClickFor(
    side: ConflictSide,
    onUseLocal: () -> Unit,
    onUseRemote: () -> Unit,
): () -> Unit = { if (side == ConflictSide.Local) onUseLocal() else onUseRemote() }

/** The newer position first; ties fall back to the local position first. */
private fun sortedOffers(
    localOffer: PositionOffer,
    remoteOffer: PositionOffer,
): List<Pair<PositionOffer, ConflictSide>> =
    listOf(localOffer to ConflictSide.Local, remoteOffer to ConflictSide.Remote)
        .sortedWith(compareByDescending { (offer, _) -> offer.isLatest })

/**
 * The option card as one button: 88dp minimum, radius 16, tile fill, 1.5dp chip border
 * (e-ink 2dp outline), chevron on the right; the newer card gets a 2dp accent border.
 * Reads as one announcement: "78 percent, chapter 8, The Crossing, this phone,
 * 12 minutes ago, latest".
 */
@Composable
private fun PositionOfferCard(
    offer: PositionOffer,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    val shape = RoundedCornerShape(16.dp)
    val borderWidth = when {
        offer.isLatest -> 2.dp
        eink -> 2.dp
        else -> 1.5.dp
    }
    val borderColor = if (offer.isLatest && !eink) colors.accent else colors.chipBorder
    val description = listOfNotNull(
        positionPercentSpoken(offer.percent),
        offer.chapter,
        offer.whereWhen,
        stringResource(StringRes.position_conflict_latest).takeIf { offer.isLatest },
    ).joinToString(", ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(shape)
            .background(colors.chip)
            .border(borderWidth, borderColor, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${offer.percent}%",
                    style = Ember.type.meta.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    color = colors.ink,
                )
                if (offer.isLatest) {
                    Spacer(Modifier.width(8.dp))
                    LatestPill()
                }
            }
            offer.chapter?.let { chapter ->
                Spacer(Modifier.size(2.dp))
                Text(
                    text = chapter,
                    style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            offer.whereWhen?.let { whereWhen ->
                Spacer(Modifier.size(2.dp))
                Text(
                    text = whereWhen,
                    style = Ember.type.meta.copy(fontSize = 13.sp),
                    color = colors.ink2,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.accentText,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.ink2,
            )
        }
    }
}

/** The black-on-accent "Latest" mark; e-ink uses black with white text only (§E-ink). */
@Composable
private fun LatestPill() {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    Text(
        text = stringResource(StringRes.position_conflict_latest),
        style = Ember.type.label.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
        color = if (eink) colors.bg else colors.onAccent,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (eink) colors.ink else colors.accent)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
