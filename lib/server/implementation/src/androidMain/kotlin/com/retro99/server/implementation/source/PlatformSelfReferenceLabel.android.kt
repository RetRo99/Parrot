package com.retro99.server.implementation.source

import android.content.res.Resources

/** Android: a phone until the system says the smallest window is tablet-sized. */
internal actual fun platformSelfReferenceLabel(): String? = runCatching {
    val smallestWidth = Resources.getSystem().configuration.smallestScreenWidthDp
    if (smallestWidth >= TABLET_SMALLEST_WIDTH_DP) "This tablet" else "This phone"
}.getOrNull()

private const val TABLET_SMALLEST_WIDTH_DP = 600
