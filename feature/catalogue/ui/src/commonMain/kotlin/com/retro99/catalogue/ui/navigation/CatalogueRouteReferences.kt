package com.retro99.catalogue.ui.navigation

import com.retro99.server.api.CatalogueTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.koin.core.annotation.Single

/**
 * What a catalogue route carries in place of an address (plan §10.6).
 *
 * A place in a catalogue is a [CatalogueTarget]: it holds the full address, which can contain
 * a key, and must not be written into navigation state. A route gets a short reference to it
 * from here. The reference means something only while this process lives and only for the
 * catalogue it was made for; after a restart it resolves to nothing and the screen starts
 * again from the catalogue's first page.
 */
@Single
class CatalogueRouteReferences {
    private data class Key(val sourceId: String, val reference: String)
    private data class State(val next: Long = 1, val entries: Map<Key, Any> = emptyMap())

    private val state = MutableStateFlow(State())

    /** A reference for [target] of the catalogue [sourceId], safe to put in a route. */
    fun referenceTo(sourceId: String, target: CatalogueTarget): String = remember(sourceId, target)

    fun target(sourceId: String, reference: String): CatalogueTarget? =
        state.value.entries[Key(sourceId, reference)] as? CatalogueTarget

    /** Forgets every reference of [sourceId]: the catalogue was turned off, removed or changed. */
    fun forget(sourceId: String) = state.update { current -> current.copy(entries = current.entries.filterKeys { it.sourceId != sourceId }) }

    /** Forgets everything: the profile changed. */
    fun clear() = state.update { it.copy(entries = emptyMap()) }

    private fun remember(sourceId: String, value: Any): String {
        val before = state.getAndUpdate { current ->
            val key = Key(sourceId, REFERENCE_PREFIX + current.next)
            // The map keeps insertion order, so dropping from the front drops the oldest.
            val kept = if (current.entries.size >= MAX_REFERENCES) current.entries.entries.drop(current.entries.size - MAX_REFERENCES + 1).associate { it.toPair() } else current.entries
            State(current.next + 1, kept + (key to value))
        }
        return REFERENCE_PREFIX + before.next
    }

    companion object {
        /** More than a back stack ever holds; older places are reached again from the first page. */
        const val MAX_REFERENCES = 200
        private const val REFERENCE_PREFIX = "r"
    }
}
