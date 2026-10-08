package com.retro99.parrot.fixtures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.Ember
import com.retro99.catalogue.domain.CatalogueDescriptionFormat
import com.retro99.catalogue.domain.sanitizeCatalogueDescription
import com.retro99.catalogue.ui.description.CatalogueDescriptionText

/**
 * Book catalogue (OPDS) fixtures: one per design board, drawn by the production composables
 * from state built here. No network, account or database.
 *
 * To add a board, add one `fixture(view = "...", expect = "...") { ... }` line below. `view`
 * is the board's name without "opds-" (design/ember/catalogues/screens/opds-<view>-<theme>.png)
 * and names the capture: design/screens/catalogue-<view>-<theme>.png. `expect` is a text that
 * is on screen only when the fixture drew what it should; catalogue_capture.py reads both
 * from this file and fails the capture without it. Keep each call's `view = "..."` and
 * `expect = "..."` on one line, as literals.
 */
class CatalogueFixture(val view: String, val expect: String, val content: @Composable () -> Unit)

private fun fixture(view: String, expect: String, content: @Composable () -> Unit) = CatalogueFixture(view, expect, content)

val catalogueFixtures: List<CatalogueFixture> = listOf(
    // Not a board: the description block of the book page, to prove the harness end to end.
    fixture(view = "descriptionText", expect = "Maps & notes") {
        FixturePage {
            CatalogueDescriptionText(
                sanitizeCatalogueDescription(
                    """<p><b>Winner of the Hugo Award.</b> A <i>sweeping</i> tale of the salt roads.</p>
                       <p>This edition includes:</p>
                       <ul><li>A new foreword</li><li>Maps &amp; notes<ol><li>The coast</li><li>The inland sea</li></ol></li></ul>
                       <p>See <a href="https://publisher.example/book">the publisher's page</a>.<br>© 2004 Ines Varga</p>
                       <script>document.title = "must not appear"</script><img src="x" onerror="alert(1)">""",
                    CatalogueDescriptionFormat.Html,
                ),
            )
        }
    },
)

@Composable
fun CatalogueFixtureScreen(view: String) {
    val fixture = catalogueFixtures.firstOrNull { it.view == view }
    if (fixture == null) {
        // Shown, not thrown: the capture script then reports the missing text and the view name.
        FixturePage { Text("Unknown catalogue fixture: $view", color = Ember.colors.error) }
    } else {
        fixture.content()
    }
}

/** For fixtures of a part of a screen. A fixture of a whole screen draws the screen itself. */
@Composable
private fun FixturePage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ember.colors.bg).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) { content() }
}
