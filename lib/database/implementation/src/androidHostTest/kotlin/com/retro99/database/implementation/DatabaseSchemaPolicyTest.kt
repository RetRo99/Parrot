package com.retro99.database.implementation

import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseSchemaPolicyTest {
    @Test
    fun `only supported versions open without recreation`() {
        // Given
        val cases = listOf(
            Triple(0L, 32L, DatabaseOpenAction.Recreate),
            Triple(27L, 32L, DatabaseOpenAction.Recreate),
            Triple(28L, 28L, DatabaseOpenAction.Open),
            Triple(28L, 32L, DatabaseOpenAction.Open),
            Triple(32L, 32L, DatabaseOpenAction.Open),
            Triple(33L, 32L, DatabaseOpenAction.Recreate),
        )

        cases.forEach { (storedVersion, currentVersion, expected) ->
            // When
            val action = decideDatabaseOpenAction(storedVersion, currentVersion)

            // Then
            assertEquals(expected, action, "$storedVersion -> $currentVersion")
        }
    }
}
