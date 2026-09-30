package com.retro99.login.domain

import com.retro99.base.server.ServerType

/** Outcome of checking what lives at a server address. */
sealed interface ServerProbeResult {
    /** The address hosts a [serverType] server. */
    data class Found(
        val serverType: ServerType,
        val supportsBrowserSignIn: Boolean,
    ) : ServerProbeResult

    /** Nothing answered at the address. */
    data object Unreachable : ServerProbeResult

    /** The address answered, but it is neither Storyteller nor Audiobookshelf. */
    data object NotSupported : ServerProbeResult
}
