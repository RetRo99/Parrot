package com.retro99.sync.domain

/** Maps a library/cloud server identifier to its currently linked delivery account. */
fun interface ProgressAccountResolver {
    suspend fun accountId(serverId: String): String?
}
