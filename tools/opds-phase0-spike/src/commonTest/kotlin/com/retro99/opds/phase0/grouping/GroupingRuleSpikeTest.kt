package com.retro99.opds.phase0.grouping

import com.retro99.opds.phase0.platformTag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 0 spike for §11.7 "Still open 1": the generic "one book with editions
 * versus a list" rule. The candidate rule, verified against the
 * Gutenberg-shaped two-step fixtures and a Calibre-shaped feed:
 *
 * A listing entry **without acquisition links** whose target is an
 * **unpaginated** acquisition feed whose entries **share one title** is a
 * single book with editions; **anything else** is a list of books (the
 * `editionsList` design fallback with the telling line on same-title
 * siblings).
 *
 * A wrong guess degrades to `editionsList` — the rule's failure mode must be
 * the less-committed one. The inputs are resolved summaries of the shared
 * fixtures (`opds/opds1/listing.xml` entries → `verses-acquisition.xml`, the
 * `/series/synthesis` paginated feed, and `calibre-newest.xml`, where entries
 * carry their own acquisition links); Phase 1 wires the real parser output to
 * this classification.
 */
class GroupingRuleSpikeTest {

    /** What the listing entry promises on its own (parsed, not guessed). */
    data class ListingEntry(
        val hasAcquisitionLinks: Boolean,
        val targetHref: String?,
    )

    /** The fetched target document, reduced to the classification inputs. */
    data class AcquisitionFeedShape(
        val paginated: Boolean,
        val entryTitles: List<String>,
    )

    sealed interface TargetResolution {
        data class Acquisisation(val feed: AcquisitionFeedShape) : TargetResolution
        data object NavigationFeed : TargetResolution
        data object Unfetched : TargetResolution
    }

    data class Decision(val oneBookWithEditions: Boolean, val rationale: String)

    fun classify(listing: ListingEntry, target: TargetResolution): Decision = when {
        listing.hasAcquisitionLinks ->
            Decision(false, "the listing entry itself acquires; it is a book, not a grouping point")
        listing.targetHref == null ->
            Decision(false, "no subsection link; a normal listing entry")
        target is TargetResolution.Acquisisation ->
            when {
                target.feed.paginated ->
                    Decision(false, "target acquisition feed is paginated; not a single work's editions")
                target.feed.entryTitles.distinct().size == 1 && target.feed.entryTitles.isNotEmpty() ->
                    Decision(true, "unpaginated acquisition feed whose entries share one title")
                else ->
                    Decision(false, "target entries do not share one title")
            }
        else ->
            Decision(false, "target is unknown/another navigation feed; degrade to the list view")
    }

    @Test
    fun `gutenbergstyle_navigation_to_a_twoedition_acquisition_feed_is_one_book`() {
        // opds/opds1/listing.xml first entry → opds/opds1/verses-acquisition.xml
        val decision = classify(
            listing = ListingEntry(hasAcquisitionLinks = false, targetHref = "/works/verses"),
            target = TargetResolution.Acquisisation(
                AcquisitionFeedShape(
                    paginated = false,
                    entryTitles = listOf(
                        "A Synthesized Book of Verses",
                        "A Synthesized Book of Verses",
                    ),
                ),
            ),
        )
        assertEquals(true, decision.oneBookWithEditions, "${platformTag}: ${decision.rationale}")
    }

    @Test
    fun `paginated_target_degrades_to_a_list`() {
        val decision = classify(
            listing = ListingEntry(hasAcquisitionLinks = false, targetHref = "/series/synthesis"),
            target = TargetResolution.Acquisisation(
                AcquisitionFeedShape(
                    paginated = true,
                    entryTitles = listOf("Volume i", "Volume ii", "Volume iii"),
                ),
            ),
        )
        assertEquals(false, decision.oneBookWithEditions, decision.rationale)
    }

    @Test
    fun `mixed_titles_degrade_to_a_list`() {
        val decision = classify(
            listing = ListingEntry(hasAcquisitionLinks = false, targetHref = "/works/anthologies"),
            target = TargetResolution.Acquisisation(
                AcquisitionFeedShape(paginated = false, entryTitles = listOf("One Title", "Another Title")),
            ),
        )
        assertEquals(false, decision.oneBookWithEditions, decision.rationale)
    }

    @Test
    fun `a_calibre_entry_with_its_own_acquisition_links_is_never_a_grouping_point`() {
        val decision = classify(
            listing = ListingEntry(hasAcquisitionLinks = true, targetHref = null),
            target = TargetResolution.Unfetched,
        )
        assertEquals(false, decision.oneBookWithEditions, decision.rationale)
    }

    @Test
    fun `an_unfetched_target_degrades_to_the_list_view`() {
        val decision = classify(
            listing = ListingEntry(hasAcquisitionLinks = false, targetHref = "/works/verses"),
            target = TargetResolution.Unfetched,
        )
        assertEquals(
            false,
            decision.oneBookWithEditions,
            "${platformTag}: a wrong guess must degrade to editionsList (${decision.rationale})",
        )
    }

    @Test
    fun `a_navigation_target_is_not_an_acquisition`() {
        // opds/opds1/listing.xml "Plain folders" entry: target is another navigation feed.
        val decision = classify(
            listing = ListingEntry(hasAcquisitionLinks = false, targetHref = "/folders/top"),
            target = TargetResolution.NavigationFeed,
        )
        assertEquals(false, decision.oneBookWithEditions, decision.rationale)
    }
}
