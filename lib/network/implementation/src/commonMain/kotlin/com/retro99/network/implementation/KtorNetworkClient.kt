package com.retro99.network.implementation

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.NetworkAnalyticsEvent
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.path
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import retro99.network.api.NetworkClient
import retro99.network.api.QueryParamsScope

/**
 * Ktor-based implementation of NetworkClient.
 *
 * @param httpClient The Ktor HttpClient to use for requests
 * @param baseUrlProvider A function that returns the base URL for requests
 * @param analytics Analytics for logging errors
 */
class KtorNetworkClient(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String?,
    private val analytics: Analytics,
) : NetworkClient {

    override suspend fun <T> getWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit
    ): AppResult<T> {
        val url = buildUrl(path, queryBuilder)
        return performRequestWithTypeInfo(typeInfo, path) {
            httpClient.get(url) {
                headers(headers)
            }
        }
    }

    override suspend fun <T> postWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit
    ): AppResult<T> {
        val url = buildUrl(path, queryBuilder)
        return performRequestWithTypeInfo(typeInfo, path) {
            httpClient.post(url) {
                headers(headers)
                body?.let { setBody(it) }
                contentType(ContentType.Application.Json)
            }
        }
    }

    override suspend fun <T> patchWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit
    ): AppResult<T> {
        val url = buildUrl(path, queryBuilder)
        return performRequestWithTypeInfo(typeInfo, path) {
            httpClient.patch(url) {
                headers(headers)
                body?.let { setBody(it) }
                contentType(ContentType.Application.Json)
            }
        }
    }

    override suspend fun <T> deleteWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        body: Any?,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit
    ): AppResult<T> {
        val url = buildUrl(path, queryBuilder)
        return performRequestWithTypeInfo(typeInfo, path) {
            httpClient.delete(url) {
                headers(headers)
                body?.let { setBody(it) }
                contentType(ContentType.Application.Json)
            }
        }
    }

    override suspend fun <T> postFormWithTypeInfo(
        path: String,
        typeInfo: TypeInfo,
        formData: Map<String, String>,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit
    ): AppResult<T> {
        val url = buildUrl(path, queryBuilder)
        return performRequestWithTypeInfo(typeInfo, path) {
            httpClient.submitForm(
                url = url,
                formParameters = Parameters.build {
                    formData.forEach { (key, value) ->
                        append(key, value)
                    }
                }
            ) {
                headers(headers)
            }
        }
    }

    override suspend fun downloadFile(
        path: String,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<ByteArray> = withContext(Dispatchers.IO) {
        val url = buildUrl(path, queryBuilder)
        try {
            val response = httpClient.get(url) {
                headers(headers)
            }

            if (response.status.isSuccess()) {
                Ok(response.bodyAsBytes())
            } else {
                handleHttpError(response, path)
            }
        } catch (e: CancellationException) {
            throw e // Re-throw cancellation exceptions to allow proper coroutine cancellation
        } catch (e: Exception) {
            ensureActive()
            handleException(e, path)
        }
    }

    override suspend fun downloadFileToPath(
        path: String,
        destinationPath: String,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<String> = withContext(Dispatchers.IO) {
        val url = buildUrl(path, queryBuilder)
        try {
            // Use prepareGet for streaming - this doesn't buffer the entire response in memory
            httpClient.prepareGet(url) {
                headers(headers)
            }.execute { response ->

                if (response.status.isSuccess()) {
                    val channel = response.bodyAsChannel()
                    writeChannelToFile(channel, destinationPath)
                    Ok(destinationPath)
                } else {
                    handleHttpError(response, path)
                }
            }
        } catch (e: CancellationException) {
            throw e // Re-throw cancellation exceptions to allow proper coroutine cancellation
        } catch (e: Exception) {
            ensureActive()
            handleException(e, path)
        }
    }

    override suspend fun downloadFileToPathWithProgress(
        path: String,
        destinationPath: String,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
        queryBuilder: QueryParamsScope.() -> Unit,
        headers: HeadersBuilder.() -> Unit,
    ): AppResult<String> = withContext(Dispatchers.IO) {
        val url = buildUrl(path, queryBuilder)
        try {
            // Use prepareGet for streaming - this doesn't buffer the entire response in memory
            httpClient.prepareGet(url) {
                headers(headers)
            }.execute { response ->

                if (response.status.isSuccess()) {
                    val channel = response.bodyAsChannel()
                    // Get Content-Length header for progress calculation
                    val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    writeChannelToFileWithProgress(
                        channel = channel,
                        destinationPath = destinationPath,
                        totalBytes = contentLength,
                        onProgress = onProgress,
                    )
                    Ok(destinationPath)
                } else {
                    handleHttpError(response, path)
                }
            }
        } catch (e: CancellationException) {
            throw e // Re-throw cancellation exceptions to allow proper coroutine cancellation
        } catch (e: Exception) {
            ensureActive()
            handleException(e, path)
        }
    }

    @PublishedApi
    internal fun buildUrl(path: String, queryBuilder: QueryParamsScope.() -> Unit): String {
        val queryParamsScope = QueryParamsScope()
        queryBuilder(queryParamsScope)

        val baseUrl = baseUrlProvider()
            ?: error("Server URL not configured. Please login first.")

        val urlBuilder = URLBuilder(baseUrl).apply {
            path(path)
            queryParamsScope.params.forEach { (key, value) ->
                when (value) {
                    is String, is Number, is Boolean -> parameters.append(key, value.toString())
                    is List<*> -> value.filterNotNull()
                        .forEach { parameters.append(key, it.toString()) }

                    null -> Unit // Ignore null parameters
                    else -> parameters.append(key, value.toString()) // Default case
                }
            }
        }
        return urlBuilder.buildString()
    }

    private suspend fun <T> performRequestWithTypeInfo(
        typeInfo: TypeInfo,
        endpoint: String,
        block: suspend () -> HttpResponse,
    ): AppResult<T> = withContext(Dispatchers.IO) {
        try {
            val response = block()
            handleResponseWithTypeInfo(response, typeInfo, endpoint)
        } catch (e: CancellationException) {
            throw e // Re-throw cancellation exceptions to allow proper coroutine cancellation
        } catch (e: Exception) {
            ensureActive()
            handleException(e, endpoint)
        }
    }

    private suspend fun <T> handleResponseWithTypeInfo(
        response: HttpResponse,
        typeInfo: TypeInfo,
        endpoint: String,
    ): AppResult<T> {
        return if (response.status.isSuccess()) {
            parseSuccessResponseWithTypeInfo(response, typeInfo)
        } else {
            handleHttpError(response, endpoint)
        }
    }

    private suspend fun <T> parseSuccessResponseWithTypeInfo(
        response: HttpResponse,
        typeInfo: TypeInfo
    ): AppResult<T> {
        return try {
            Ok(response.body(typeInfo))
        } catch (e: Exception) {
            Err(
                AppError.ApiError(
                    code = 0,
                    message = "Failed to parse response: ${e.message}"
                )
            )
        }
    }

    private suspend fun handleHttpError(
        response: HttpResponse,
        endpoint: String,
    ): AppResult<Nothing> {
        val errorBody = response.bodyAsText()
        val errorCode = response.status.value
        val error = when (errorCode) {
            in 400..499 -> handleClientError(errorCode, errorBody)
            in 500..599 -> Err(
                AppError.ApiError(
                    code = errorCode,
                    message = "Server error: $errorBody"
                )
            )

            else -> Err(
                AppError.ApiError(
                    code = errorCode,
                    message = "HTTP error $errorCode: $errorBody"
                )
            )
        }
        analytics.logEvent(
            NetworkAnalyticsEvent.NetworkRequestFailed(
                endpoint = endpoint,
                errorType = "http_error",
                isTimeout = false,
                isConnectivity = false,
                statusCode = errorCode,
            ),
        )
        return error
    }

    private fun handleClientError(errorCode: Int, errorBody: String): AppResult<Nothing> {
        return when (errorCode) {
            401, 403 -> Err(
                AppError.ApiError(
                    code = errorCode,
                    message = "Authentication error: $errorBody"
                )
            )

            404 -> Err(
                AppError.ApiError(
                    code = errorCode,
                    message = "Resource not found: $errorBody"
                )
            )

            else -> Err(
                AppError.ApiError(
                    code = errorCode,
                    message = "Client error: $errorBody"
                )
            )
        }
    }

    private fun handleException(e: Exception, endpoint: String): AppResult<Nothing> {
        val failure = classifyNetworkFailure(e)

        // Transport failures are operation outcomes; callers decide whether a propagated
        // unexpected failure materially affects the user before reporting a Crashlytics issue.
        analytics.logEvent(
            NetworkAnalyticsEvent.NetworkRequestFailed(
                endpoint = endpoint,
                errorType = failure.errorType,
                isTimeout = failure.isTimeout,
                isConnectivity = failure.isConnectivity,
            )
        )

        return when {
            e is SerializationException -> Err(
                AppError.ApiError(
                    code = 0,
                    message = "Failed to parse response: ${e.message}"
                )
            )

            e is IOException || e is ConnectTimeoutException || e is SocketTimeoutException ||
                failure.isExpectedFailure -> handleNetworkException(e, failure)

            else -> Err(AppError.UnknownError(e))
        }
    }

    private fun handleNetworkException(
        e: Exception,
        failure: NetworkFailureClassification,
    ): AppResult<Nothing> {
        return Err(
            AppError.NetworkError(
                throwable = e,
                isConnectivity = failure.isConnectivity,
                isTimeout = failure.isTimeout,
                isExpectedFailure = failure.isExpectedFailure,
            )
        )
    }

    override fun close() {
        httpClient.close()
    }
}
