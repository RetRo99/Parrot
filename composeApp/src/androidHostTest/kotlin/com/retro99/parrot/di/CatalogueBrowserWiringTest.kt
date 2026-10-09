package com.retro99.parrot.di

import com.retro99.catalogue.ui.browse.CatalogueBrowseViewModel
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertFalse

/** The catalogue browser's screen can be made from the generated graph, for a first page and for a stale reference. */
class CatalogueBrowserWiringTest {
    @Test
    fun `the browser screen resolves from the real graph`() = RealAppGraph().use { graph ->
        listOf("", "r404").forEach { reference ->
            val viewModel = graph.koin.get<CatalogueBrowseViewModel> { parametersOf("no-such-catalogue", reference) }
            assertFalse(viewModel.browser.state.value.navigation != null)
            viewModel.browser.cancel()
        }
    }
}
