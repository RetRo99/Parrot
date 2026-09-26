package com.retro99.network.implementation

internal data class NetworkFailureClassification(
    val errorType: String,
    val isTimeout: Boolean,
    val isConnectivity: Boolean,
    val isExpectedFailure: Boolean,
)

/** Classifies expected transport failures without placing throwable messages in telemetry. */
internal fun classifyNetworkFailure(throwable: Throwable): NetworkFailureClassification {
    val causes = generateSequence(throwable) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    val typeNames = causes.mapNotNull { it::class.simpleName }.toSet()
    val message = causes.firstNotNullOfOrNull { it.message?.lowercase() }.orEmpty()

    return when {
        typeNames.any { it in CONNECT_TIMEOUT_TYPES } ->
            NetworkFailureClassification("connect_timeout", isTimeout = true, isConnectivity = false, isExpectedFailure = true)

        typeNames.any { it in SOCKET_TIMEOUT_TYPES } ->
            NetworkFailureClassification("socket_timeout", isTimeout = true, isConnectivity = false, isExpectedFailure = true)

        typeNames.any { it in DNS_FAILURE_TYPES } || message.contains("unable to resolve host") ||
            message.contains("host not found") ->
            NetworkFailureClassification("dns_resolution_failed", isTimeout = false, isConnectivity = true, isExpectedFailure = true)

        typeNames.any { it in ROUTE_FAILURE_TYPES } || message.contains("network is unreachable") ->
            NetworkFailureClassification("network_unreachable", isTimeout = false, isConnectivity = true, isExpectedFailure = true)

        typeNames.any { it in CONNECTION_FAILURE_TYPES } || message.contains("connection refused") ||
            message.contains("connection reset") ->
            NetworkFailureClassification("connection_failed", isTimeout = false, isConnectivity = true, isExpectedFailure = true)

        typeNames.any { it in TLS_FAILURE_TYPES } || message.contains("ssl") || message.contains("tls") ->
            NetworkFailureClassification("ssl_error", isTimeout = false, isConnectivity = false, isExpectedFailure = true)

        message.contains("broken pipe") ->
            NetworkFailureClassification("broken_pipe", isTimeout = false, isConnectivity = true, isExpectedFailure = true)

        else ->
            NetworkFailureClassification("unknown", isTimeout = false, isConnectivity = false, isExpectedFailure = false)
    }
}

private val CONNECT_TIMEOUT_TYPES = setOf("ConnectTimeoutException", "HttpRequestTimeoutException")
private val SOCKET_TIMEOUT_TYPES = setOf("SocketTimeoutException")
private val DNS_FAILURE_TYPES = setOf("UnknownHostException", "UnresolvedAddressException")
private val ROUTE_FAILURE_TYPES = setOf("NoRouteToHostException")
private val CONNECTION_FAILURE_TYPES = setOf("ConnectException", "ConnectionException")
private val TLS_FAILURE_TYPES = setOf("SSLHandshakeException", "SSLPeerUnverifiedException")

private const val MAX_CAUSE_DEPTH = 8
