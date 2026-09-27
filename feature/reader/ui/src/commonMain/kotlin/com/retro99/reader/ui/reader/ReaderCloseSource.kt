package com.retro99.reader.ui.reader

/** Stable, non-sensitive source for an accepted Reader close/navigation action. */
enum class ReaderCloseSource(val entryPoint: String) {
    CloseButton("close_button"),
    SystemBack("system_back"),
}
