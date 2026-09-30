package com.getlingara.kotlin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** One request as the fake server saw it. */
class Seen(
    val method: String,
    val path: String,
    private val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? = headers[name]?.firstOrNull()
}

/** Answers one exchange. */
typealias Handler = (HttpExchange) -> Unit

/** `com.sun.net.httpserver` on 127.0.0.1:0, recording every request: the unit suite's server. */
class FakeServer : AutoCloseable {
    private val http: HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            start()
        }
    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    fun on(
        path: String,
        handler: Handler,
    ): FakeServer {
        http.createContext(path) { exchange ->
            val body = String(exchange.requestBody.readAllBytes(), Charsets.UTF_8)
            val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value }
            seen.add(Seen(exchange.requestMethod, exchange.requestURI.path, headers, body))
            hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        return this
    }

    fun hits(path: String): Int = hits[path]?.get() ?: 0

    fun uri(): URI = URI.create("http://127.0.0.1:${http.address.port}")

    fun uri(path: String): URI = URI.create("${uri()}$path")

    override fun close() = http.stop(0)
}

object Fakes {
    /** Writes a whole response. */
    fun respond(
        exchange: HttpExchange,
        status: Int,
        contentType: String?,
        body: String,
    ) {
        val bytes = body.toByteArray()
        if (contentType != null) exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    fun json(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) = respond(exchange, status, "application/json", body)

    /** A token endpoint answering [accessToken] with [expiresIn]. */
    fun token(
        accessToken: String,
        expiresIn: Long,
    ): Handler =
        { exchange ->
            json(exchange, 200, """{"access_token":"$accessToken","token_type":"Bearer","expires_in":$expiresIn}""")
        }

    /** Answers the n-th request with the n-th handler, and every later one with the last. */
    fun script(vararg steps: Handler): Handler {
        val next = AtomicInteger()
        return { exchange -> steps[minOf(next.getAndIncrement(), steps.size - 1)](exchange) }
    }

    /** A status with at most one header and a JSON body. */
    fun status(
        status: Int,
        header: String?,
        value: String?,
        body: String,
    ): Handler =
        { exchange ->
            if (header != null) exchange.responseHeaders.set(header, value)
            json(exchange, status, body)
        }

    fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/** A clock that moves only when a test says so. */
class SettableClock : Clock() {
    private val millis = AtomicLong(1_790_000_000_000L)

    fun advance(by: Duration) {
        millis.addAndGet(by.inWholeMilliseconds)
    }

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(millis.get())
}

/** Records each requested wait and returns at once. */
class RecordingSleeper {
    val sleeps: MutableList<Duration> = Collections.synchronizedList(mutableListOf())
    val sleeper: suspend (Duration) -> Unit = { sleeps.add(it) }
}
