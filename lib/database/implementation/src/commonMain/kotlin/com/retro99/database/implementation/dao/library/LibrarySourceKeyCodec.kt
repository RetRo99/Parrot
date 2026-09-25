package com.retro99.database.implementation.dao.library

import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceConnectionId

/** Local storage key only. A portable sync payload must use explicit account fields. */
internal object LibrarySourceKeyCodec {
    fun encode(key: SourceBookKey): String {
        val account = key.accountIdentity
        val fields = when (account) {
            is SourceAccountIdentity.Portable -> listOf(
                key.adapterId.value,
                "portable",
                account.backendId,
                account.accountId,
                "",
                key.nativeBookId.value,
            )
            is SourceAccountIdentity.Unresolved -> listOf(
                key.adapterId.value,
                "unresolved",
                "",
                "",
                account.connectionId.value,
                key.nativeBookId.value,
            )
        }
        return buildString {
            append("v1|")
            fields.forEach { field ->
                append(field.length)
                append(':')
                append(field)
            }
        }
    }

    fun decode(profileId: LibraryProfileId, encoded: String): SourceBookKey {
        require(encoded.startsWith("v1|")) { "Unknown source-key storage version" }
        var offset = 3
        fun readField(): String {
            val separator = encoded.indexOf(':', offset)
            require(separator > offset) { "Malformed source-key field length" }
            val length = encoded.substring(offset, separator).toIntOrNull()
            require(length != null && length >= 0) { "Malformed source-key field length" }
            val start = separator + 1
            require(length <= encoded.length - start) { "Truncated source-key field" }
            val end = start + length
            offset = end
            return encoded.substring(start, end)
        }
        val adapterId = readField()
        val kind = readField()
        val backendId = readField()
        val accountId = readField()
        val unresolvedConnectionId = readField()
        val nativeBookId = readField()
        require(offset == encoded.length) { "Trailing source-key data" }
        val account = when (kind) {
            "portable" -> {
                require(unresolvedConnectionId.isEmpty())
                SourceAccountIdentity.Portable(backendId, accountId)
            }
            "unresolved" -> {
                require(backendId.isEmpty() && accountId.isEmpty())
                SourceAccountIdentity.Unresolved(SourceConnectionId(unresolvedConnectionId))
            }
            else -> error("Unknown source identity kind: $kind")
        }
        return SourceBookKey(
            profileId = profileId,
            adapterId = LibraryAdapterId(adapterId),
            accountIdentity = account,
            nativeBookId = NativeBookId(nativeBookId),
        )
    }
}
