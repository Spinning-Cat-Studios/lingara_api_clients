package com.getlingara.kotlin.conformance

import com.getlingara.kotlin.DeprecationNotice
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.LingaraClientBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.ServerSocket
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One case's client, built from its `client` block through the DSL only, with a virtual clock, a
 * recording sleeper and, when the case asks, a recording deprecation hook.
 */
internal class Rig private constructor() {
    private val now = AtomicLong(CLOCK_START)
    private val sleeps: MutableList<Duration> = Collections.synchronizedList(mutableListOf())
    private val hooks: MutableList<JsonElement> = Collections.synchronizedList(mutableListOf())
    lateinit var client: LingaraClient
        private set

    fun advance(seconds: Long) {
        now.addAndGet(seconds)
    }

    /** Clears what one step records, before it runs. */
    fun reset() {
        sleeps.clear()
        hooks.clear()
    }

    fun sleepsSeconds(): List<Long> = synchronized(sleeps) { sleeps.map { (it.inWholeMilliseconds / 1000.0).roundToLong() } }

    fun hookCalls(): List<JsonElement> = synchronized(hooks) { hooks.toList() }

    private fun record(notice: DeprecationNotice) {
        hooks +=
            buildJsonObject {
                put("version", notice.version)
                put("deprecated_at", notice.deprecatedAt?.epochSecond)
                put("sunset_at", notice.sunsetAt?.epochSecond)
                notice.link?.let { link ->
                    putJsonObject("link") {
                        put("raw", link.raw)
                        put("target", link.target?.toString())
                    }
                }
            }
    }

    /** Advances only when a case says so. */
    private inner class VirtualClock : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochSecond(now.get())
    }

    companion object {
        /** Where the virtual clock starts for every case (README, Comparison rules). */
        const val CLOCK_START = 1_790_000_000L

        fun build(
            block: JsonObject,
            base: String,
            token: String,
        ): Rig {
            val rig = Rig()
            val unreachable = block.text("base_url") == "unreachable"
            val baseUrl = if (unreachable) closedPort() else base
            rig.client =
                LingaraClient {
                    this.baseUrl = URI.create(baseUrl)
                    tokenUrl = URI.create(if (unreachable) "$baseUrl/oauth/token" else token)
                    clock = rig.VirtualClock()
                    sleeper = { rig.sleeps.add(it) }
                    options(block)
                    if (block.text("deprecation_hook") == "record") onDeprecation(rig::record)
                }
            return rig
        }

        /** The rest of the client block, onto the public builder. */
        private fun LingaraClientBuilder.options(block: JsonObject) {
            (block["credentials"] as? JsonObject)?.let { credentials ->
                clientCredentials(credentials.text("client_id").orEmpty(), credentials.text("client_secret").orEmpty())
                if (credentials.text("auth") == "post") clientSecretPost()
            }
            block["scopes"]?.let { scopes = it.jsonArray.map { s -> s.jsonPrimitive.content } }
            block.text("version")?.let { version = it }
            (block["retries"] as? JsonObject)?.let { retries ->
                retries.number("max_attempts")?.let { maxAttempts = it.toInt() }
                retries.number("retry_after_cap_s")?.let { retryAfterCap = it.seconds }
            }
            block.text("user_agent_suffix")?.let { userAgentSuffix = it }
            block.number("stream_idle_timeout_ms")?.let { streamIdleTimeout = it.milliseconds }
        }

        /** A port bound and released, so nothing listens on it. */
        private fun closedPort(): String = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}" }
    }
}

/** A string field, or `null` when absent, `null` or not a string. */
internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** A numeric field, or `null`. */
internal fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull()
