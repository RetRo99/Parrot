package com.retro99.database.implementation

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.analytics.api.Analytics
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.database.api.DatabaseExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlin.coroutines.cancellation.CancellationException

@Single(binds = [DatabaseExecutor::class])
class DatabaseExecutorImpl(
    private val analytics: Analytics,
) : DatabaseExecutor {

    override suspend fun <T> executeDatabaseOperation(
        reportException: Boolean,
        operation: suspend () -> T,
    ): AppResult<T> =
        withContext(Dispatchers.IO) {
            try {
                Ok(operation())
            } catch (e: Exception) {
                ensureActive() // Check if coroutine is still active

                if (reportException) {
                    analytics.logException(e, "Error executing dao operation")
                }

                when (e) {
                    is CancellationException -> throw e // Re-throw cancellation exceptions

                    else -> {
                        // Check if it's a database-related exception by examining the exception class name
                        val isDatabaseException =
                            e::class.simpleName?.contains("SQL", ignoreCase = true) == true ||
                                    e::class.simpleName?.contains(
                                        "Database",
                                        ignoreCase = true
                                    ) == true

                        if (isDatabaseException) {
                            // Extract table name from error message if possible
                            val tableName = extractTableName(e.message ?: "")
                            Err(
                                AppError.DatabaseError(
                                    throwable = e,
                                    table = tableName
                                )
                            )
                        } else {
                            Err(AppError.UnknownError(throwable = e))
                        }
                    }
                }
            }
        }

    /**
     * Attempts to extract a table name from SQLite error messages.
     * This is a best-effort approach as error message formats can vary.
     */
    private fun extractTableName(errorMessage: String): String? {
        val tablePatterns = listOf(
            "table\\s+['`\"]?([\\w_]+)['`\"]?".toRegex(RegexOption.IGNORE_CASE),
            "constraint on\\s+['`\"]?([\\w_]+)['`\"]?".toRegex(RegexOption.IGNORE_CASE)
        )

        for (pattern in tablePatterns) {
            val match = pattern.find(errorMessage)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }

        return null
    }
}
