package com.retro99.analytics.implementation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticLogFilesTest {
    @Test
    fun removesActiveAndRotatedLogsAndVerifiesBothAbsent() {
        val files = mutableSetOf("active-private-path", "backup-private-path")

        clearDiagnosticLogFiles(
            paths = listOf("active-private-path", "backup-private-path"),
            exists = files::contains,
            delete = { path -> files.remove(path) },
        )

        assertTrue(files.isEmpty())
    }

    @Test
    fun missingFilesAreAnOrdinarySuccessfulNoOp() {
        val files = mutableSetOf("active-private-path")
        var deletions = 0

        clearDiagnosticLogFiles(
            paths = listOf("active-private-path", "backup-private-path"),
            exists = files::contains,
            delete = { path ->
                deletions++
                files.remove(path)
            },
        )

        assertEquals(1, deletions)
        assertTrue(files.isEmpty())
    }

    @Test
    fun falseDeleteResultWithFileStillPresentFailsWithoutLeakingPath() {
        val path = "private/path/with-user-data"
        val files = mutableSetOf(path)

        val failure = assertFailsWith<IllegalStateException> {
            clearDiagnosticLogFiles(
                paths = listOf(path),
                exists = files::contains,
                delete = { false },
            )
        }

        assertFalse(failure.message.orEmpty().contains(path))
        assertEquals(setOf(path), files)
    }

    @Test
    fun postconditionFailureIsDetectedEvenWhenDeleteClaimsSuccess() {
        val path = "active-private-path"

        assertFailsWith<IllegalStateException> {
            clearDiagnosticLogFiles(
                paths = listOf(path),
                exists = { true },
                delete = { true },
            )
        }
    }

    @Test
    fun platformExceptionIsReplacedWithBoundedFailure() {
        val failure = assertFailsWith<IllegalStateException> {
            clearDiagnosticLogFiles(
                paths = listOf("private/path"),
                exists = { throw SecurityException("private/path permission detail") },
                delete = { false },
            )
        }

        assertEquals("Diagnostic logs could not be cleared", failure.message)
        assertEquals(null, failure.cause)
    }
}
