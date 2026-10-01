package com.retro99.server.api

/**
 * Where a stored position came from. Stored in `position.origin`; never sent to a server.
 */
enum class PositionOrigin(val value: String) {
    /** Reading or listening by the person on this device. */
    User("user"),

    /** Put back after a restore. Not reading. */
    Restore("restore"),

    /** Pulled from a server, and not an echo of our own write. */
    Remote("remote"),

    /** Written from another linked copy, or pulled back as an echo of such a write. */
    LinkedCopy("linked_copy"),

    /** Applied by the person in the positions panel. */
    Manual("manual"),
    ;

    /** Real reading wins when copies are compared (P1). */
    val isRealReading: Boolean
        get() = this == User || this == Remote || this == Manual

    companion object {
        fun fromValue(value: String?): PositionOrigin =
            entries.firstOrNull { origin -> origin.value == value } ?: User
    }
}
