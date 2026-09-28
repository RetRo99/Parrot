package com.retro99.analytics.implementation

import com.retro99.analytics.api.DiagnosticContext

/**
 * A sanitized throwable preserves the exception type/cause structure without retaining any
 * exception message, which may contain request data, file paths, or user-authored content.
 */
internal class SanitizedDiagnosticException(
    val sourceType: String,
    cause: Throwable?,
) : Exception("Handled failure ($sourceType)", cause)

internal expect fun sanitizeDiagnosticThrowable(throwable: Throwable): Throwable

/** Free-form context is omitted; only validated fields from DiagnosticContext survive. */
internal fun sanitizeDiagnosticMessage(message: String?): String? = message
    ?.let(::sanitizeStructuredDiagnosticMessage)
    ?: message?.let { "Handled failure; free-form context omitted" }

internal fun sanitizeDiagnosticContext(context: DiagnosticContext): String? {
    val fields = buildList {
        context.screen?.let { add("screen" to it) }
        context.sourceScreen?.let { add("source_screen" to it) }
        context.destinationScreen?.let { add("destination_screen" to it) }
        context.entryPoint?.let { add("entry_point" to it) }
        context.action?.let { add("action" to it) }
        context.operation?.let { add("operation" to it) }
        context.stage?.let { add("stage" to it) }
        context.outcome?.let { add("outcome" to it) }
        context.reasonCode?.let { add("reason_code" to it) }
        context.serverType?.let { add("server_type" to it) }
        context.mediaType?.let { add("media_type" to it) }
        context.correlationId?.let { add("correlation_id" to it) }
    }
    val safeFields = fields.mapNotNull { (key, value) ->
        val valid = if (key == "correlation_id") SAFE_CORRELATION_ID.matches(value) else SAFE_CONTEXT_VALUE.matches(value)
        (key to value).takeIf { valid }
    }
    if (safeFields.isEmpty()) return null
    return "diagnostic_context " + safeFields.joinToString(" ") { (key, value) -> "$key=$value" }
}

/** A successful clear must not recreate the log file with its own terminal breadcrumb. */
internal fun shouldPersistDiagnosticBreadcrumbToFile(context: DiagnosticContext): Boolean =
    context.action != "clear_logs"

private fun sanitizeStructuredDiagnosticMessage(message: String): String? {
    if (!message.startsWith("diagnostic_context ")) return null
    val allowedKeys = setOf(
        "screen", "source_screen", "destination_screen", "entry_point", "action", "operation", "stage", "outcome",
        "reason_code", "server_type", "media_type", "correlation_id",
    )
    val fields = message.removePrefix("diagnostic_context ").split(' ').mapNotNull { token ->
        val key = token.substringBefore('=', "")
        val value = token.substringAfter('=', "")
        val valid = if (key == "correlation_id") SAFE_CORRELATION_ID.matches(value) else SAFE_CONTEXT_VALUE.matches(value)
        (key to value).takeIf { key in allowedKeys && valid }
    }
    return fields.takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "diagnostic_context ", separator = " ") { (key, value) -> "$key=$value" }
}

internal fun Throwable.stableDiagnosticType(): String =
    this::class.simpleName
        ?.takeIf { SAFE_EXCEPTION_TYPE.matches(it) }
        ?: "Throwable"

private val SAFE_EXCEPTION_TYPE = Regex("[A-Za-z][A-Za-z0-9_\$]{0,79}")
private val SAFE_CONTEXT_VALUE = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
private val SAFE_CORRELATION_ID = Regex("[A-Fa-f0-9-]{16,64}")
