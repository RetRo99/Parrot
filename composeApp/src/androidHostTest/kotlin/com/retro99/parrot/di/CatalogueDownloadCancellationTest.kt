package com.retro99.parrot.di

import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueAcquisitionRequest
import com.retro99.catalogue.domain.CatalogueRequestOutcome
import com.github.michaelbull.result.get
import com.retro99.server.api.CatalogueAccountEditor
import com.retro99.server.api.CatalogueAcquisitionRepository
import com.retro99.server.api.CatalogueFeedDocument
import com.retro99.server.api.CatalogueRepositoryProvider
import com.retro99.server.api.OpdsAccountDetails
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.user.api.UserRegistry
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A download running in the app's own graph stops when its catalogue is turned off, removed,
 * or gets other account details. Nothing here is built by hand: the registry, the queue and
 * the catalogue session are the ones the app resolves.
 */
class CatalogueDownloadCancellationTest {

    @Test
    fun `turning the catalogue off cancels its running download`() = cancelsRunningDownload { koin, source ->
        koin.get<ServerRegistry>().deactivateServer(source.id)
    }

    @Test
    fun `removing the catalogue cancels its running download`() = cancelsRunningDownload { koin, source ->
        koin.get<ServerRegistry>().removeServer(source.id)
    }

    @Test
    fun `saving account details cancels the download running without them`() = cancelsRunningDownload { koin, source ->
        koin.get<CatalogueAccountEditor>().saveAccount(source.id, OpdsAccountDetails("patron", "secret"))
    }

    @Test
    fun `removing account details cancels the running download`() = cancelsRunningDownload { koin, source ->
        koin.get<ServerRegistry>().clearCredentials(source.id)
    }

    @Test
    fun `moving the catalogue to another address cancels its running download`() = cancelsRunningDownload { koin, source ->
        koin.get<ServerRegistry>().updateServer(source.copy(baseUrl = "https://elsewhere.example/opds/"))
    }

    private fun cancelsRunningDownload(change: suspend (Koin, ServerConfig) -> Unit) {
        val fileRequested = CompletableDeferred<Unit>()
        val fileRequestStopped = CompletableDeferred<Unit>()
        val graph = RealAppGraph { request ->
            if (request.url.encodedPath.endsWith("/one")) {
                fileRequested.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    fileRequestStopped.complete(Unit)
                }
            }
            respond(FEED, headers = headersOf(HttpHeaders.ContentType, "application/opds+json"))
        }
        graph.use {
            runBlocking {
                withTimeout(TIMEOUT_MILLIS) {
                    // Given a download of this catalogue that is in the middle of its file
                    val users = graph.koin.get<UserRegistry>()
                    users.createProfile("a", "A", null)
                    users.setActiveProfile("a")
                    val source = graph.koin.get<ServerRegistry>().addServerWithId("source", "Books", ServerType.Opds, ROOT)
                    val repository = graph.koin.get<CatalogueRepositoryProvider>().getRepository(source.id)
                    val document = assertIs<CatalogueFeedDocument>(assertNotNull(repository).getRoot().get())
                    val publication = document.publications.single()
                    val locator = assertNotNull(
                        assertIs<CatalogueAcquisitionRepository>(repository)
                            .locate(document, publication, publication.acquisitionChoices.single()),
                    )
                    val queue = graph.koin.get<CatalogueAcquisitionManager>()
                    val outcome = queue.request(
                        CatalogueAcquisitionRequest(
                            sourceId = source.id,
                            publicationKey = locator.publicationKey,
                            representationKey = locator.representationKey,
                            detailIdentity = null,
                            listingUrl = locator.documentUrl,
                            title = "Book",
                            author = null,
                            coverReference = null,
                            catalogueName = "Books",
                        ),
                    )
                    assertIs<CatalogueRequestOutcome.Queued>(outcome)
                    fileRequested.await()
                    assertEquals(listOf(AcquisitionState.Downloading), queue.observeAcquisitions().first().map { it.state })

                    // When
                    change(graph.koin, source)

                    // Then the request is gone, its network call was stopped and nothing is left on disk
                    fileRequestStopped.await()
                    assertEquals(emptyList(), queue.observeAcquisitions().first())
                    assertTrue(graph.stagingRoot.walk().none { it.isFile }, "a partial file was left behind")
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
        const val ROOT = "https://books.example/opds/"
        const val FEED = """{"metadata":{"title":"Books"},"publications":[{"metadata":{"title":"Book","identifier":"urn:book:1","language":"en"},"links":[{"href":"one","rel":"http://opds-spec.org/acquisition/open-access","type":"application/epub+zip"}]}]}"""
    }
}
