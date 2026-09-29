package com.retro99.base.ui.compose

/**
 * User-selectable theme setting. [System] follows the device: e-ink on e-ink displays,
 * otherwise Night or Day depending on the system dark mode.
 */
enum class ThemeMode(val key: String) {
    System("system"),
    Night("night"),
    Day("day"),
    Eink("eink"),
    ;

    companion object {
        fun fromKey(key: String?): ThemeMode? = entries.firstOrNull { mode -> mode.key == key }
    }
}

/**
 * Resolves the stored [ThemeMode] into a concrete [EmberMode]. With nothing stored the app
 * is Night, except on e-ink displays where a dark screen is never rendered.
 */
fun resolveEmberMode(
    themeMode: ThemeMode?,
    isSystemDark: Boolean,
    isEinkDevice: Boolean,
): EmberMode = when (themeMode) {
    ThemeMode.Night -> EmberMode.Night
    ThemeMode.Day -> EmberMode.Day
    ThemeMode.Eink -> EmberMode.Eink
    ThemeMode.System -> when {
        isEinkDevice -> EmberMode.Eink
        isSystemDark -> EmberMode.Night
        else -> EmberMode.Day
    }
    null -> if (isEinkDevice) EmberMode.Eink else EmberMode.Night
}
