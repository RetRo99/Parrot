package com.retro99.reader.ui.tts

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A voice pack host on 127.0.0.1 for [TtsModelStoreTest]: it serves a manifest and a few
 * small assets with their real SHA-256 values, honours `Range` the way GitHub's asset CDN
 * does, and can be told to answer slowly or to cut an answer short so a download has to be
 * resumed. Every request it sees is recorded, so a test can assert on the resume offset.
 */
internal class TtsModelStoreServer {

    data class Request(val path: String, val range: String?)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val assets = ConcurrentHashMap<String, ByteArray>()

    /** One-shot: the named asset's next answer stops after this many bytes. */
    private val truncateAfter = ConcurrentHashMap<String, Int>()

    /** Milliseconds of pause before each 64 KB block of the named asset. */
    private val throttle = ConcurrentHashMap<String, Long>()

    val requests = CopyOnWriteArrayList<Request>()

    @Volatile
    var manifestBody: String = ""

    @Volatile
    var manifestDelayMs: Long = 0L

    val port: Int get() = server.address.port

    init {
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    fun putAsset(version: String, name: String, bytes: ByteArray) {
        assets["$version/$name"] = bytes
    }

    fun truncateNextAnswer(version: String, name: String, afterBytes: Int) {
        truncateAfter["$version/$name"] = afterBytes
    }

    fun throttle(version: String, name: String, millisPerBlock: Long) {
        throttle["$version/$name"] = millisPerBlock
    }

    fun requestsFor(version: String, name: String): List<Request> =
        requests.filter { request -> request.path == "$version/$name" }

    fun assetUrl(version: String, name: String): String =
        TtsModelManifestValidator.TRUSTED_URL_PREFIX + "$version/$name"

    /** Rewrites a trusted GitHub asset address onto this server. */
    fun localAddressFor(url: String): String =
        if (url.startsWith(TtsModelManifestValidator.TRUSTED_URL_PREFIX)) {
            "http://127.0.0.1:$port/" +
                url.removePrefix(TtsModelManifestValidator.TRUSTED_URL_PREFIX)
        } else {
            url
        }

    val manifestAddress: String get() = "http://127.0.0.1:$port/manifest.json"

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/")
        val range = exchange.requestHeaders.getFirst("Range")
        requests += Request(path, range)
        try {
            if (path == MANIFEST_PATH) {
                if (manifestDelayMs > 0L) Thread.sleep(manifestDelayMs)
                val body = manifestBody.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
                return
            }
            val bytes = assets[path]
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1L)
                return
            }
            val offset = range
                ?.let { header -> RANGE.find(header)?.groupValues?.get(1)?.toIntOrNull() }
                ?: 0
            if (offset > bytes.size) {
                exchange.sendResponseHeaders(416, -1L)
                return
            }
            val body = bytes.copyOfRange(offset, bytes.size)
            if (offset > 0) {
                exchange.responseHeaders.add(
                    "Content-Range",
                    "bytes $offset-${bytes.size - 1}/${bytes.size}",
                )
                exchange.sendResponseHeaders(206, body.size.toLong())
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
            }
            val limit = truncateAfter.remove(path)?.coerceAtMost(body.size) ?: body.size
            val pausePerBlock = throttle[path] ?: 0L
            var written = 0
            while (written < limit) {
                val count = minOf(BLOCK, limit - written)
                if (pausePerBlock > 0L) Thread.sleep(pausePerBlock)
                exchange.responseBody.write(body, written, count)
                exchange.responseBody.flush()
                written += count
            }
        } catch (ignored: Exception) {
            // A test that cuts an answer short or stops the server mid-answer lands here.
        } finally {
            exchange.close()
        }
    }

    /**
     * Accepts the connection and never answers, like a captive portal: the client sits in
     * its read until its own read timeout.
     */
    internal class SilentHost {

        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private val held = CopyOnWriteArrayList<java.net.Socket>()

        @Volatile
        private var running = true

        val address: String get() = "http://127.0.0.1:${socket.localPort}/manifest.json"

        init {
            Thread {
                while (running) {
                    try {
                        held += socket.accept()
                    } catch (ignored: Exception) {
                        return@Thread
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        fun stop() {
            running = false
            held.forEach { connection -> runCatching { connection.close() } }
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val MANIFEST_PATH = "manifest.json"
        private const val BLOCK = 64 * 1024
        private val RANGE = Regex("bytes=([0-9]+)-")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") {
                byte -> "%02x".format(byte.toInt() and 0xFF)
            }
    }
}
