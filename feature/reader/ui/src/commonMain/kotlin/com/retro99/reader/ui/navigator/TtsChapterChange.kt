package com.retro99.reader.ui.navigator

/** The locator collector's stop/clear/reload decision, independent of Android. */
internal fun shouldReloadTtsChapter(
    previousHref: String?,
    newHref: String,
    narratedHref: String?,
): Boolean = previousHref != null && previousHref != newHref
