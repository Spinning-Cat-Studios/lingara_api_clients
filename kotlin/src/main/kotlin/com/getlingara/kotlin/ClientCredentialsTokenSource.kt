package com.getlingara.kotlin

import com.getlingara.kotlin.internal.Endpoint
import com.getlingara.kotlin.internal.ErrorMapper
import com.getlingara.kotlin.internal.HeadersSeen
import com.getlingara.kotlin.internal.LibraryScope
import com.getlingara.kotlin.internal.LingaraJson
import com.getlingara.kotlin.internal.Policy
import com.getlingara.kotlin.internal.Retry
import com.getlingara.kotlin.internal.awaitCancelling
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * The OAuth 2.0 client-credentials grant: the [TokenSource] a client builds from
 * `clientCredentials(…)` (CONTRACT.md K1; ADR 29.9.26s D6).
 *
 * It caches one token and replaces it `min(60 s, expires_in / 2)` before it expires. Concurrent
 * callers share one exchange, which runs in the library's scope and never in a caller's coroutine,
 * so a caller that is cancelled abandons only its own wait: the exchange runs on and caches its
 * token for the next caller. Each HTTP attempt of the exchange is bounded by the token request
 * timeout, response body included, because no waiter can cancel it.
 */
public class ClientCredentialsTokenSource internal constructor(
    private val clientId: String,
    private val secret: ClientSecret,
    private val exchange: TokenExchange,
) : TokenSource {
    /** The slot's states. Every transition is a compare-and-set, so no lock is held on an await. */
    private sealed interface State

    private object Empty : State

    private class Cached(
        val token: AccessToken,
        val staleAt: Instant,
    ) : State

    private class Flight(
        val deferred: Deferred<AccessToken>,
    ) : State

    private val slot = AtomicReference<State>(Empty)

    /**
     * Returns the cached token while it is fresh, and otherwise awaits the one exchange, starting it
     * if none is running. Cancelling the caller cancels only its wait.
     */
    override suspend fun token(): AccessToken = flight().await()

    /** Forgets [token] only if it is still cached; during an exchange it is a no-op. */
    override fun invalidate(token: AccessToken) {
        val state = slot.get()
        if (state is Cached && state.token == token) slot.compareAndSet(state, Empty)
    }

    /**
     * The fresh token, the running flight, or a new one. A new flight is created unstarted in the
     * library's scope; the caller that wins the compare-and-set starts it, and a loser cancels its
     * unstarted copy and joins the winner's.
     */
    private fun flight(): Deferred<AccessToken> {
        while (true) {
            val state = slot.get()
            if (state is Cached && exchange.policy.clock.instant() < state.staleAt) {
                return CompletableDeferred(state.token)
            }
            if (state is Flight) return state.deferred
            // A lazily created Deferred cannot name its own val, so the block reaches its state here.
            lateinit var mine: Flight
            val deferred = LibraryScope.async(start = CoroutineStart.LAZY) { fly(mine) }
            mine = Flight(deferred)
            if (slot.compareAndSet(state, mine)) {
                deferred.start()
                return deferred
            }
            deferred.cancel()
        }
    }

    /**
     * Runs the exchange, then writes the cache and only afterwards completes: a waiter that meets a
     * 401 at once then finds the token it was handed, to clear. Nothing is cached on failure.
     */
    private suspend fun fly(mine: Flight): AccessToken {
        val result =
            try {
                exchange()
            } catch (e: Throwable) {
                slot.compareAndSet(mine, Empty)
                throw e
            }
        slot.compareAndSet(mine, result)
        return result.token
    }

    private suspend fun exchange(): Cached {
        val policy = exchange.policy
        var sentAt = policy.clock.instant()
        val response =
            Retry.withRetries(policy) {
                // obtained_at is when the request that succeeded was sent.
                sentAt = policy.clock.instant()
                post()
            }
        val status = response.statusCode()
        val body = response.body().toByteArray()
        if (status !in 200..299) {
            throw ErrorMapper.refusal(Endpoint.TOKEN, status, response.headers(), body, policy.clock.instant())
        }
        return grant(body, sentAt)
    }

    /**
     * One attempt under the library's own timer. `withTimeoutOrNull`, never `withTimeout`, whose
     * exception would complete the flight as cancelled and look like each waiter's own cancellation.
     */
    private suspend fun post(): HttpResponse<String> =
        withTimeoutOrNull(exchange.timeout) {
            try {
                exchange.http.sendAsync(request(), HeadersSeen(HttpResponse.BodyHandlers.ofString())).awaitCancelling()
            } catch (e: IOException) {
                throw ErrorMapper.transport(e, false, listOf(secret.exposeSecret()))
            }
        } ?: throw TransportException(TransportKind.TIMEOUT, null)

    private fun request(): HttpRequest {
        val builder =
            HttpRequest
                .newBuilder(exchange.tokenUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("User-Agent", exchange.userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(form()))
        if (!exchange.secretPost) builder.header("Authorization", basic(clientId, secret.exposeSecret()))
        return builder.build()
    }

    /** The grant, any scopes, and the credentials only under client_secret_post: never both. */
    private fun form(): String {
        val fields = mutableListOf("grant_type=client_credentials")
        if (exchange.scopes.isNotEmpty()) fields += "scope=" + encode(exchange.scopes.joinToString(" "))
        if (exchange.secretPost) {
            fields += "client_id=" + encode(clientId)
            fields += "client_secret=" + encode(secret.exposeSecret())
        }
        return fields.joinToString("&")
    }

    override fun toString(): String =
        "ClientCredentialsTokenSource(clientId=$clientId, clientSecret=$secret, tokenUrl=${exchange.tokenUrl})"

    internal companion object {
        /** `Basic base64(form(id) ":" form(secret))`, each half form-encoded per RFC 6749 §2.3.1. */
        fun basic(
            id: String,
            secret: String,
        ): String = "Basic " + Base64.getEncoder().encodeToString("${encode(id)}:${encode(secret)}".toByteArray())

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

        /** A 200's token and stale point; malformed unless Bearer with a token and a lifetime. */
        private fun grant(
            body: ByteArray,
            sentAt: Instant,
        ): Cached {
            val json =
                try {
                    LingaraJson.parseToJsonElement(String(body, Charsets.UTF_8)) as? JsonObject
                } catch (e: IllegalArgumentException) {
                    null
                }
            val token = ErrorMapper.text(json, "access_token")
            val expiresIn = (json?.get("expires_in") as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            val bearer = ErrorMapper.text(json, "token_type").equals("bearer", ignoreCase = true)
            if (token == null || expiresIn == null || expiresIn < 0 || !bearer) {
                throw TransportException(TransportKind.MALFORMED_RESPONSE, null)
            }
            val lifetime = (expiresIn * 1000).toLong()
            val skew = minOf(60_000L, lifetime / 2)
            return Cached(AccessToken(token), sentAt.plusMillis(lifetime - skew))
        }
    }
}

/** Everything the exchange needs besides the credentials, read from the builder once. */
internal class TokenExchange(
    options: LingaraClientBuilder,
    val http: HttpClient,
    val userAgent: String,
) {
    val tokenUrl: URI = options.tokenUrl
    val secretPost: Boolean = options.secretPost
    val scopes: List<String> = options.scopes.toList()
    val policy: Policy = options.policy()
    val timeout: Duration = options.tokenRequestTimeout
}
