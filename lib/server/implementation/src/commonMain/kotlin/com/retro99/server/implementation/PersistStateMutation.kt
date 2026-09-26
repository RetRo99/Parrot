package com.retro99.server.implementation

/** Restores the in-memory snapshot if durable persistence rejects the proposed state. */
internal fun <T> persistStateMutation(
    previousValue: T,
    updatedValue: T,
    update: (T) -> Unit,
    persist: () -> Unit,
) {
    update(updatedValue)
    try {
        persist()
    } catch (failure: Throwable) {
        update(previousValue)
        throw failure
    }
}
