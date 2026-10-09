package com.retro99.opds.implementation.cache

import com.retro99.opds.api.OpdsCacheEntry
import com.retro99.opds.api.OpdsCacheKey
import com.retro99.opds.api.OpdsFeedCache
import com.retro99.opds.api.model.OpdsBudgets
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** LRU cache bounded by retained payload bytes and entry count; no persistent storage. */
class MemoryOpdsFeedCache(
    private val maxEntries: Int = OpdsBudgets.MAX_CACHE_ENTRIES,
    private val maxBytes: Long = OpdsBudgets.MAX_CACHE_BYTES,
) : OpdsFeedCache {
    init { require(maxEntries > 0 && maxBytes > 0) { "cache budgets must be positive" } }
    private val mutex = Mutex()
    private val entries = linkedMapOf<OpdsCacheKey, OpdsCacheEntry>()
    private var bytes = 0L

    override suspend fun load(key: OpdsCacheKey): OpdsCacheEntry? = mutex.withLock {
        entries.remove(key)?.also { entries[key] = it }?.snapshot()
    }
    override suspend fun store(key: OpdsCacheKey, entry: OpdsCacheEntry) = mutex.withLock {
        remove(key)
        if (entry.cacheControl.hasNoStore() || entry.body.bytes.size > maxBytes) return@withLock
        val snapshot = entry.snapshot()
        entries[key] = snapshot
        bytes += snapshot.body.bytes.size
        while (entries.size > maxEntries || bytes > maxBytes) remove(entries.keys.first())
    }
    override suspend fun invalidate(key: OpdsCacheKey) = mutex.withLock { remove(key) }
    override suspend fun clearAll() = mutex.withLock { entries.clear(); bytes = 0L }
    private fun remove(key: OpdsCacheKey) { entries.remove(key)?.let { bytes -= it.body.bytes.size } }
    private fun OpdsCacheEntry.snapshot() = copy(body = body.copy(bytes = body.bytes.copyOf()), validators = validators.toMap(), cacheControl = cacheControl.toList())
}

internal fun List<String>.hasNoStore() = flatMap { it.split(',') }.any { it.trim().substringBefore('=').equals("no-store", true) }
