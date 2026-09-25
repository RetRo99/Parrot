package com.retro99.server.api.library

/** Stable namespace for Local identities derived from verified whole-file hashes. */
object LocalContentIdentity {
    const val ADAPTER_ID = "local"
    const val BACKEND_ID = "local-content"
    const val ACCOUNT_ID = "whole-file-sha-256-v1"
    const val HASH_ALGORITHM = "sha-256-v1"
    private val digestPattern = Regex("[0-9a-fA-F]{64}")

    fun canonicalHash(value: String?): String? =
        value?.takeIf { hash -> digestPattern.matches(hash) }?.lowercase()

    fun nativeBookId(hash: String): NativeBookId {
        require(canonicalHash(hash) == hash.lowercase())
        return NativeBookId("$HASH_ALGORITHM:${hash.lowercase()}")
    }

    fun isPortableNativeBookId(value: String): Boolean {
        val prefix = "$HASH_ALGORITHM:"
        if (!value.startsWith(prefix)) return false
        return canonicalHash(value.removePrefix(prefix)) != null
    }
}
