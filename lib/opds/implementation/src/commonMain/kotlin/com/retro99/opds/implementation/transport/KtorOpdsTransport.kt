package com.retro99.opds.implementation.transport

import com.retro99.opds.api.*
import com.retro99.opds.api.model.OpdsBudgets
import com.retro99.opds.implementation.url.Rfc3986ReferenceResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import kotlin.io.encoding.Base64
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/** One isolated client per source, owning its engine. No auth/cookie/logging plugins or shared default headers. */
class KtorOpdsTransport(
    private val engine: HttpClientEngine,
    private val catalogueRoot: String,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val log: (String) -> Unit = {},
) : OpdsTransport {
    private val resolver = Rfc3986ReferenceResolver()
    private val trustedOrigin = origin(Url(catalogueRoot))
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 30_000 }
    }
    override fun close() { client.close(); engine.close() }

    override suspend fun fetch(request: OpdsRequest): OpdsFetchResult =
        run(request, streaming = false, wrap = { it }) { response, status, current, privateNetwork, failure ->
            if (response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > OpdsBudgets.MAX_RESPONSE_BYTES } == true) {
                failure(OpdsTransportError.Code.RESPONSE_TOO_LARGE, status)
            } else {
                val channel = response.bodyAsChannel()
                val chunks = mutableListOf<ByteArray>()
                val buffer = ByteArray(8192)
                var count = 0
                var exceeded = false
                try { while (true) {
                    // One-byte overflow probe at the boundary; never retain excess bytes.
                    val read = channel.readAvailable(buffer, 0, minOf(buffer.size, OpdsBudgets.MAX_RESPONSE_BYTES.toInt() - count + 1))
                    if (read < 0) break
                    if (read == 0) continue
                    if (count.toLong() + read > OpdsBudgets.MAX_RESPONSE_BYTES) { exceeded = true; channel.cancel(null); break }
                    chunks += buffer.copyOf(read)
                    count += read
                } } finally { channel.cancel(null) }
                if (exceeded) failure(OpdsTransportError.Code.RESPONSE_TOO_LARGE, status) else {
                    val body = ByteArray(count)
                    var offset = 0
                    chunks.forEach { it.copyInto(body, offset); offset += it.size }
                    OpdsFetchResult.Response(status, response.headers.entries().associate { it.key.lowercase() to it.value }, body, current, crossOriginPrivateNetwork = privateNetwork)
                }
            }
        }

    /**
     * Streams the body to [sink] in chunks; the file is never held in memory. Redirects, origin
     * checks and credentials follow exactly the rules of [fetch].
     */
    override suspend fun download(request: OpdsRequest, sink: OpdsDownloadSink, maxBytes: Long): OpdsDownloadResult =
        run<OpdsDownloadResult>(request, streaming = true, wrap = { OpdsDownloadResult.Failure(it.error, it.crossOriginPrivateNetwork) }) { response, status, current, privateNetwork, failure ->
            fun failed(code: OpdsTransportError.Code): OpdsDownloadResult = failure(code, status).let { OpdsDownloadResult.Failure(it.error, it.crossOriginPrivateNetwork) }
            val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it >= 0 }
            if (declared != null && declared > maxBytes) {
                val tooLarge = failure(OpdsTransportError.Code.RESPONSE_TOO_LARGE, status)
                return@run OpdsDownloadResult.Failure(tooLarge.error.copy(declaredLength = declared), tooLarge.crossOriginPrivateNetwork)
            }
            // A failing sink (disk full, file gone) is the caller's error, not a network one.
            var sinkFailure: Throwable? = null
            suspend fun toSink(block: suspend () -> Unit): Boolean = try { block(); true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { sinkFailure = error; false }
            if (!toSink { sink.start(declared) }) return@run OpdsDownloadResult.SinkFailure(sinkFailure!!)
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            var count = 0L
            var exceeded = false
            try { while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                if (count + read > maxBytes) { exceeded = true; break }
                if (!toSink { sink.write(buffer, read) }) break
                count += read
            } } finally { channel.cancel(null) }
            sinkFailure?.let { return@run OpdsDownloadResult.SinkFailure(it) }
            if (exceeded) return@run failed(OpdsTransportError.Code.RESPONSE_TOO_LARGE)
            if (declared != null && count != declared) return@run failed(OpdsTransportError.Code.LENGTH_MISMATCH)
            OpdsDownloadResult.Complete(status, count, declared, current, response.headers[HttpHeaders.ContentType], privateNetwork)
        }

    private suspend fun <R> run(
        request: OpdsRequest,
        streaming: Boolean,
        wrap: (OpdsFetchResult.Failure) -> R,
        body: suspend (response: HttpResponse, status: Int, current: String, privateNetwork: Boolean, failure: (OpdsTransportError.Code, Int?) -> OpdsFetchResult.Failure) -> R,
    ): R {
        val rootContext = request.isCatalogueRoot ?: (request.url == catalogueRoot)
        var privateNetwork = false
        fun failure(code: OpdsTransportError.Code, status: Int? = null, retry: Long? = null) =
            OpdsFetchResult.Failure(OpdsTransportError(code, status, retryAfterMillis = retry, isCatalogueRoot = rootContext), privateNetwork)
        var current = request.url
        var redirects = 0
        var leftOrigin = false
        val visited = mutableSetOf<String>()
        try {
            while (true) {
                // Password-over-HTTP is more specific than the anonymous cleartext policy.
                val invalid = validate(current, request.allowCleartext || redirects == 0 && request.credentials is OpdsCredentials.Basic)
                if (invalid != null) return wrap(failure(invalid))
                val url = Url(current)
                if (origin(url) != trustedOrigin) leftOrigin = true
                current = url.toString().substringBefore('#')
                if (request.credentials is OpdsCredentials.Basic && url.protocol.name == "http" && redirects == 0) return wrap(failure(OpdsTransportError.Code.PASSWORD_OVER_HTTP))
                if (!visited.add(current)) return wrap(failure(OpdsTransportError.Code.REDIRECT_LOOP))
                if (origin(url) != trustedOrigin && localAddress(url.host)) privateNetwork = true
                log("opds.request")
                val step: Step<R> = client.prepareGet(current) {
                    // A whole-request deadline would cut off a large file; the socket timeout still applies.
                    if (streaming) timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                    headers.append(HttpHeaders.Accept, request.acceptMediaTypes.joinToString(", "))
                    if (!leftOrigin && origin(url) == trustedOrigin && url.protocol.name == "https" && request.credentials is OpdsCredentials.Basic) {
                        val credential = request.credentials as OpdsCredentials.Basic
                        headers.append(HttpHeaders.Authorization, "Basic ${Base64.Default.encode("${credential.username}:${credential.password}".encodeToByteArray())}")
                    }
                    if (redirects == 0) request.cacheValidators.orEmpty().forEach { (name, value) ->
                        if (name.equals(HttpHeaders.IfNoneMatch, true) || name.equals(HttpHeaders.IfModifiedSince, true)) headers.append(name, value)
                    }
                }.execute { response ->
                    val status = response.status.value
                    if (status in REDIRECT_STATUSES) {
                        val location = response.headers[HttpHeaders.Location]
                        if (location == null) Step.Done(wrap(failure(OpdsTransportError.Code.REDIRECT_MISSING_LOCATION, status))) else Step.Redirect(location)
                    } else if (status >= 400) {
                        val code = when (status) {
                            401 -> if (hasBasic(response.headers.getAll(HttpHeaders.WWWAuthenticate).orEmpty())) OpdsTransportError.Code.SIGN_IN_NEEDED else OpdsTransportError.Code.SIGN_IN_METHOD_UNSUPPORTED
                            403 -> OpdsTransportError.Code.FORBIDDEN
                            404 -> OpdsTransportError.Code.NOT_FOUND
                            429 -> OpdsTransportError.Code.RATE_LIMITED
                            503 -> OpdsTransportError.Code.SERVICE_UNAVAILABLE
                            in 400..499 -> OpdsTransportError.Code.CLIENT_ERROR
                            else -> OpdsTransportError.Code.SERVER_ERROR
                        }
                        Step.Done(wrap(failure(code, status, if (status == 429 || status == 503) retryAfter(response.headers[HttpHeaders.RetryAfter]) else null)))
                    } else {
                        val current = current
                        Step.Done(body(response, status, current, privateNetwork) { code, at -> failure(code, at) })
                    }
                }
                when (step) {
                    is Step.Done -> { log("opds.complete"); return step.result }
                    is Step.Redirect -> {
                        if (redirects >= OpdsBudgets.MAX_REDIRECTS) return wrap(failure(OpdsTransportError.Code.REDIRECT_LIMIT))
                        val target = resolver.resolve(current, step.location)
                        if (url.protocol.name == "https" && target.startsWith("http:", true)) return wrap(failure(OpdsTransportError.Code.REDIRECT_SCHEME_DOWNGRADE))
                        current = target
                        redirects++
                        log("opds.redirect")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("opds.failure")
            return wrap(failure(networkError(error)))
        }
    }

    private fun validate(value: String, allowHttp: Boolean): OpdsTransportError.Code? {
        val scheme = value.substringBefore(':', "").lowercase()
        if (scheme.isEmpty()) return OpdsTransportError.Code.MALFORMED_URL
        if (scheme !in setOf("https", "http")) return OpdsTransportError.Code.UNSUPPORTED_SCHEME
        if (!value.startsWith("$scheme://", true) || value.any { it.isWhitespace() || it.code < 32 }) return OpdsTransportError.Code.MALFORMED_URL
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return OpdsTransportError.Code.MALFORMED_URL
        val url = try { Url(value) } catch (_: Exception) { return OpdsTransportError.Code.MALFORMED_URL }
        if (url.host.isEmpty() || '%' in url.host || url.user != null || url.password != null) return OpdsTransportError.Code.MALFORMED_URL
        if (scheme == "http" && !allowHttp) return OpdsTransportError.Code.CLEARTEXT_NOT_ALLOWED
        return null
    }

    private fun origin(url: Url) = Triple(url.protocol.name.lowercase(), url.host.lowercase(), url.port)

    private fun localAddress(host: String): Boolean {
        val h = host.lowercase().removeSurrounding("[", "]").trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan")) return true
        if (':' in h) {
            val words = ipv6Words(h) ?: return false
            if (words.take(7).all { it == 0 } && words.last() in 0..1) return true
            if (words[0] and 0xfe00 == 0xfc00 || words[0] and 0xffc0 == 0xfe80) return true
            if (words.take(5).all { it == 0 } && words[5] == 0xffff) {
                return localAddress("${words[6] shr 8}.${words[6] and 255}.${words[7] shr 8}.${words[7] and 255}")
            }
            return false
        }
        val octets = h.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] in setOf(0, 10, 127) || octets[0] == 172 && octets[1] in 16..31 || octets[0] == 192 && octets[1] == 168 || octets[0] == 169 && octets[1] == 254
    }

    private fun ipv6Words(address: String): List<Int>? {
        var value = address
        if ('.' in value) {
            val octets = value.substringAfterLast(':').split('.').map { it.toIntOrNull() ?: return null }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return null
            value = value.substringBeforeLast(':') + ":${((octets[0] shl 8) or octets[1]).toString(16)}:${((octets[2] shl 8) or octets[3]).toString(16)}"
        }
        fun words(part: String) = if (part.isEmpty()) emptyList() else part.split(':').map { it.toIntOrNull(16) ?: -1 }
        val sections = value.split("::")
        if (sections.size > 2) return null
        val left = words(sections.first())
        val right = if (sections.size == 2) words(sections.last()) else emptyList()
        if ((left + right).any { it !in 0..0xffff }) return null
        val zeros = 8 - left.size - right.size
        if (sections.size == 1 && zeros != 0 || sections.size == 2 && zeros < 1) return null
        return left + List(zeros) { 0 } + right
    }

    private fun hasBasic(headers: List<String>): Boolean = headers.any { header ->
        var quoted = false
        var escaped = false
        val outside = buildString { for (c in header) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false; append(' ') }
            else if (c == '"') { quoted = true; append(' ') } else append(c)
        } }
        Regex("(?:^|,)\\s*Basic(?:\\s+|$)", RegexOption.IGNORE_CASE).containsMatchIn(outside)
    }

    private fun retryAfter(value: String?): Long? {
        if (value == null) return null
        value.trim().toLongOrNull()?.let { return if (it in 0..Long.MAX_VALUE / 1000) it * 1000 else null }
        return try { (value.fromHttpToGmtDate().timestamp - nowMillis()).coerceAtLeast(0) } catch (_: Exception) { null }
    }

    private fun networkError(error: Throwable): OpdsTransportError.Code {
        var cause: Throwable? = error
        repeat(8) {
            val current = cause ?: return OpdsTransportError.Code.UNREACHABLE
            val diagnostic = "${current::class.simpleName} ${current.message}".lowercase()
            if ("timeout" in diagnostic || "timed out" in diagnostic || "nsurlerrordomain" in diagnostic && "-1001" in diagnostic) return OpdsTransportError.Code.TIMEOUT
            if ("ssl" in diagnostic || "tls" in diagnostic || "certificate" in diagnostic || "nsurlerrordomain" in diagnostic && Regex("-120[0-6]").containsMatchIn(diagnostic)) return OpdsTransportError.Code.TLS_UNTRUSTED
            cause = current.cause
        }
        return OpdsTransportError.Code.UNREACHABLE
    }

    private sealed interface Step<out R> {
        data class Redirect(val location: String) : Step<Nothing>
        data class Done<R>(val result: R) : Step<R>
    }
    private companion object {
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    }
}
