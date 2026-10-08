package com.retro99.server.api

import kotlin.test.*

class ServerCapabilitiesTest {
    @Test fun existing_types_keep_library_and_reader_routing_including_local() {
        for (type in listOf(ServerType.Storyteller, ServerType.Audiobookshelf, ServerType.ParrotCloud, ServerType.Local)) {
            val capabilities = type.getCapabilities()
            assertTrue(capabilities.contributesToLibrary, type.identifier)
            assertTrue(capabilities.supportsReaderRepository, type.identifier)
            assertFalse(capabilities.supportsCatalogueBrowsing, type.identifier)
        }
        assertFalse(ServerType.Local.getCapabilities().supportsUserLibrary)
    }

    @Test fun opds_is_catalogue_only_without_remote_mutation_or_sync() {
        val capabilities = ServerType.Opds.getCapabilities()
        assertTrue(capabilities.supportsCatalogueBrowsing)
        assertFalse(capabilities.contributesToLibrary)
        assertFalse(capabilities.supportsReaderRepository)
        assertFalse(capabilities.supportsSeries)
        assertFalse(capabilities.supportsReadingProgress)
        assertFalse(capabilities.supportsBookUpload)
        assertFalse(capabilities.supportsBookDeletion)
        assertFalse(capabilities.supportsAutomaticSync)
        assertFalse(capabilities.supportsOfflineMutationQueue)
    }
}
