package com.retro99.opds.api

import com.retro99.opds.api.model.OpdsEntry
import com.retro99.opds.api.model.OpdsFeedDocument

/**
 * Generic "one book with editions versus a list" rule, decided in Phase 0
 * (docs/opds-phase0-spikes.md §3 item 5; plan §11.7 Still open 1):
 *
 * A listing entry **without acquisition links** whose target is an
 * **unpaginated** acquisition feed whose entries **share one title** is a
 * single book with editions; **anything else** is a list, with the telling
 * line on same-title siblings.
 *
 * A wrong guess degrades to the list view ([OpdsEditionDecision.ListOfBooks])
 * — the design's less-committed fallback.
 */
object OpdsGroupingRule {

    fun decide(
        listing: OpdsEntry,
        target: FetchedTarget,
    ): OpdsEditionDecision {
        if (listing.acquisitionLinkCount != 0) {
            return OpdsEditionDecision.ListOfBooks(
                rationale = "the listing entry itself acquires; it is a book, not a grouping point",
            )
        }
        val feed = when (target) {
            is FetchedTarget.AcquisitionFeed -> target.feed
            FetchedTarget.NavigationFeed ->
                return OpdsEditionDecision.ListOfBooks(rationale = "target is another navigation feed; degrade to the list view")
            FetchedTarget.Unknown ->
                return OpdsEditionDecision.ListOfBooks(rationale = "target unknown or unfetched; degrade to the list view")
        }
        if (feed == null) {
            return OpdsEditionDecision.ListOfBooks(rationale = "target missing; degrade to the list view")
        }

        return when {
            feed.pagination.next != null || feed.pagination.first != null ->
                OpdsEditionDecision.ListOfBooks(rationale = "target acquisition feed is paginated; not a single work's editions")
            feed.publications.isEmpty() ->
                OpdsEditionDecision.ListOfBooks(rationale = "target acquisition feed has no publications")
            feed.publications.map { it.title }.distinct().size == 1 ->
                OpdsEditionDecision.OneBookWithEditions(
                    rationale = "unpaginated acquisition feed whose entries share one title",
                )
            else ->
                OpdsEditionDecision.ListOfBooks(rationale = "target entries do not share one title")
        }
    }

    /** The fetched target of a navigation act (the shape the rule needs). */
    sealed interface FetchedTarget {
        data class AcquisitionFeed(val feed: OpdsFeedDocument?) : FetchedTarget
        data object NavigationFeed : FetchedTarget
        data object Unknown : FetchedTarget
    }
}

sealed interface OpdsEditionDecision {
    val rationale: String

    data class OneBookWithEditions(override val rationale: String) : OpdsEditionDecision
    data class ListOfBooks(override val rationale: String) : OpdsEditionDecision
}
