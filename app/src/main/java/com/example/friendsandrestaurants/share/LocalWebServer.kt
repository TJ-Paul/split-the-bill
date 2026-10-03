package com.example.friendsandrestaurants.share

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names are lower-case. */
    val headers: Map<String, String>,
    val body: String,
    val remoteAddress: String
)

class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        fun json(status: Int, json: String) = HttpResponse(status, "application/json; charset=utf-8", json.toByteArray())
        fun text(status: Int, text: String) = HttpResponse(status, "text/plain; charset=utf-8", text.toByteArray())
    }
}

/**
 * A small HTTP/1.1 server for the local network. It answers one request per connection
 * (`Connection: close`), which keeps it simple and is plenty for a table of friends.
 *
 * Requests are handled on a bounded pool of threads, so [handler] may block (long polling).
 * It is plain JVM code so it can be unit tested without a device.
 */
class LocalWebServer(private val handler: (HttpRequest) -> HttpResponse) {

    private var serverSocket: ServerSocket? = null
    private var executor: ThreadPoolExecutor? = null
    private val openSockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /**
     * Listens on every network interface, on the first free port of [preferredPorts]
     * (or any free port if none are). Returns the port.
     */
    @Synchronized
    @Throws(IOException::class)
    fun start(preferredPorts: List<Int>): Int {
        if (isRunning) return port
        val socket = bindFirstFree(preferredPorts)
        val threadCount = AtomicInteger()
        val pool = ThreadPoolExecutor(
            0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue()
        ) { r -> Thread(r, "bill-share-http-${threadCount.incrementAndGet()}").apply { isDaemon = true } }

        serverSocket = socket
        executor = pool
        port = socket.localPort

        Thread({ acceptLoop(socket, pool) }, "bill-share-accept").apply { isDaemon = true }.start()
        return port
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Interrupts handlers waiting in a long poll, then drops any connection still open.
        executor?.shutdownNow()
        executor = null
        openSockets.toList().forEach { runCatching { it.close() } }
        openSockets.clear()
    }

    private fun bindFirstFree(preferredPorts: List<Int>): ServerSocket {
        for (candidate in preferredPorts + 0) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(candidate), BACKLOG)
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                if (candidate == 0) throw e
            }
        }
        throw IOException("No free port")
    }

    private fun acceptLoop(server: ServerSocket, pool: ThreadPoolExecutor) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break // closed by stop()
            }
            openSockets.add(client)
            try {
                pool.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                // Too many connections at once (or stopping): drop this one, the page retries.
                openSockets.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val response = try {
                val request = readRequest(input, socket) ?: return
                handler(request)
            } catch (e: BadRequestException) {
                HttpResponse.text(e.status, e.message ?: "Bad request")
            } catch (e: SocketException) {
                return
            } catch (e: java.net.SocketTimeoutException) {
                return
            } catch (e: InterruptedException) {
                return // server stopping
            } catch (e: Exception) {
                HttpResponse.text(500, "Something went wrong on the host's phone.")
            }
            writeResponse(socket.getOutputStream(), response)
        } catch (_: IOException) {
            // Client went away; nothing to do.
        } finally {
            openSockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    class BadRequestException(val status: Int, message: String) : Exception(message)

    companion object {
        const val MAX_CONNECTIONS = 48
        private const val BACKLOG = 64
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 64 * 1024

        /** Returns null when the client closed the connection without sending anything. */
        @Throws(IOException::class, BadRequestException::class)
        fun readRequest(input: InputStream, socket: Socket? = null): HttpRequest? {
            val head = readHead(input) ?: return null
            val lines = head.split("\r\n", "\n")
            val requestLine = lines.first().split(" ")
            if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/")) {
                throw BadRequestException(400, "Bad request line")
            }
            val method = requestLine[0].uppercase()
            val target = requestLine[1]

            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon <= 0) throw BadRequestException(400, "Bad header")
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }

            if (headers["transfer-encoding"] != null) throw BadRequestException(411, "Length required")
            val length = headers["content-length"]?.let {
                it.toIntOrNull() ?: throw BadRequestException(400, "Bad Content-Length")
            } ?: 0
            if (length < 0) throw BadRequestException(400, "Bad Content-Length")
            if (length > MAX_BODY_BYTES) throw BadRequestException(413, "Request too large")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) throw BadRequestException(400, "Body ended early")
                read += n
            }

            val path = target.substringBefore('?').substringBefore('#')
            val queryString = if ('?' in target) target.substringAfter('?').substringBefore('#') else ""
            return HttpRequest(
                method = method,
                path = path,
                query = parseQuery(queryString),
                headers = headers,
                body = String(body, Charsets.UTF_8),
                remoteAddress = socket?.inetAddress?.hostAddress.orEmpty()
            )
        }

        /** Reads up to the blank line that ends the headers. */
        private fun readHead(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (buffer.size() == 0) return null
                    throw BadRequestException(400, "Headers ended early")
                }
                buffer.write(b)
                if (buffer.size() > MAX_HEADER_BYTES) throw BadRequestException(431, "Headers too large")
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A || (last4 and 0xFFFF) == 0x0A0A) break
            }
            return buffer.toString(Charsets.ISO_8859_1.name()).trimEnd()
        }

        fun parseQuery(query: String): Map<String, String> {
            if (query.isEmpty()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val key = decode(pair.substringBefore('='))
                val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
                result[key] = value
            }
            return result
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            throw BadRequestException(400, "Bad query")
        }

        fun writeResponse(output: OutputStream, response: HttpResponse) {
            val head = StringBuilder()
            head.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            head.append("Content-Type: ").append(response.contentType).append("\r\n")
            head.append("Content-Length: ").append(response.body.size).append("\r\n")
            head.append("Cache-Control: no-store\r\n")
            head.append("X-Content-Type-Options: nosniff\r\n")
            head.append("Connection: close\r\n")
            response.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            head.append("\r\n")
            output.write(head.toString().toByteArray(Charsets.UTF_8))
            output.write(response.body)
            output.flush()
        }

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            421 -> "Misdirected Request"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Status"
        }
    }
}
