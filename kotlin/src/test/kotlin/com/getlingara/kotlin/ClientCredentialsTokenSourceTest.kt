package com.getlingara.kotlin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClientCredentialsTokenSourceTest {
    private val clock = SettableClock()

    private fun source(
        server: FakeServer,
        timeout: Duration = 30.seconds,
    ): ClientCredentialsTokenSource {
        val options =
            LingaraClientBuilder().apply {
                tokenUrl = server.uri("/oauth/token")
                clock = this@ClientCredentialsTokenSourceTest.clock
                sleeper = RecordingSleeper().sleeper
                tokenRequestTimeout = timeout
            }
        val exchange = TokenExchange(options, HttpClient.newHttpClient(), "lingara-kotlin/0.0.0 (kotlin/2; jvm/17)")
        return ClientCredentialsTokenSource("lgr_cid_test", ClientSecret("lgr_cs_test"), exchange)
    }

    /** A token endpoint numbering its tokens, so a new exchange is visible in the value. */
    private fun numbered(
        expiresIn: Long,
        delayMillis: Long,
    ): Handler {
        val n = AtomicInteger()
        return { exchange ->
            Fakes.sleep(delayMillis)
            Fakes.token("lgr_at_${n.incrementAndGet()}", expiresIn)(exchange)
        }
    }

    /** Runs [call] as [n] coroutines released together by one gate; each result or failure. */
    private suspend fun CoroutineScope.together(
        n: Int,
        context: CoroutineContext = Dispatchers.Default,
        call: suspend () -> AccessToken,
    ): List<Result<AccessToken>> {
        val gate = CompletableDeferred<Unit>()
        val runs =
            (1..n).map {
                async(context) {
                    gate.await()
                    runCatching { call() }
                }
            }
        gate.complete(Unit)
        return runs.awaitAll()
    }

    /**
     * 29.9.26s AC3: eight concurrent token() coroutines cause one exchange; when it fails, all
     * eight throw a LingaraException of the same class and message, and nothing is cached.
     */
    @Test
    fun singleFlightSharesOneExchangeAndCachesNoFailure() =
        runBlocking {
            FakeServer().on("/oauth/token", numbered(3600, 200)).use { server ->
                val tokens = source(server)
                val got = together(8) { tokens.token() }
                assertEquals(1, server.hits("/oauth/token"))
                got.forEach { assertEquals(got[0].getOrThrow(), it.getOrThrow()) }
            }
            val failing: Handler = { exchange ->
                Fakes.sleep(200)
                Fakes.respond(exchange, 500, "text/html", "<html>oops</html>")
            }
            FakeServer().on("/oauth/token", failing).use { server ->
                val tokens = source(server)
                val got = together(8) { tokens.token() }.map { it.exceptionOrNull() }
                assertEquals(1, server.hits("/oauth/token"))
                val first = assertIs<OAuthException>(got[0])
                assertEquals("http_500", first.error)
                got.forEach {
                    assertIs<OAuthException>(it)
                    assertEquals(first.message, it.message)
                }
                assertFailsWith<OAuthException> { tokens.token() }
                assertEquals(2, server.hits("/oauth/token"), "a failure is never cached")
            }
        }

    /**
     * 29.9.26s AC4: with expires_in 3600 a token is reused at 3539 s and replaced at 3541 s after
     * send, and with expires_in 40 it is stale at 20 s.
     */
    @Test
    fun refreshesAtMinOfSixtySecondsAndHalfTheLifetime() =
        runBlocking {
            FakeServer().on("/oauth/token", numbered(3600, 0)).use { server ->
                val tokens = source(server)
                val first = tokens.token()
                clock.advance(3539.seconds)
                assertEquals(first, tokens.token())
                clock.advance(2.seconds)
                assertNotEquals(first, tokens.token())
                assertEquals(2, server.hits("/oauth/token"))
            }
            FakeServer().on("/oauth/token", numbered(40, 0)).use { server ->
                val tokens = source(server)
                val first = tokens.token()
                clock.advance(19.seconds)
                assertEquals(first, tokens.token())
                clock.advance(1.seconds)
                assertNotEquals(first, tokens.token())
            }
        }

    /**
     * 29.9.26s AC5: invalidate of an older token leaves a newer cached token in place; invalidate
     * during an exchange changes nothing; and a waiter that invalidates the token its flight has
     * just returned clears it from the cache.
     */
    @Test
    fun invalidateIsCompareAndClear() =
        runBlocking {
            FakeServer().on("/oauth/token", numbered(3600, 300)).use { server ->
                val tokens = source(server)
                val older = tokens.token()
                tokens.invalidate(older)
                val flying = async(Dispatchers.Default) { tokens.token() }
                delay(100)
                tokens.invalidate(older)
                val newer = flying.await()
                assertEquals(2, server.hits("/oauth/token"))
                tokens.invalidate(older)
                assertEquals(newer, tokens.token())
                assertEquals(2, server.hits("/oauth/token"), "a stale invalidate cleared nothing")
                tokens.invalidate(newer)
                assertNotEquals(newer, tokens.token())
                assertEquals(3, server.hits("/oauth/token"))
            }
        }

    /**
     * 29.9.26s AC6: cancelling the coroutine that started an exchange throws its
     * CancellationException at once, while the flight completes and its token is cached for the
     * next caller.
     */
    @Test
    fun aCancelledWaiterLeavesTheFlightRunning() =
        runBlocking {
            FakeServer().on("/oauth/token", numbered(3600, 500)).use { server ->
                val tokens = source(server)
                val waiter = launch(Dispatchers.Default) { tokens.token() }
                delay(100)
                val started = System.nanoTime()
                waiter.cancelAndJoin()
                assertTrue(waiter.isCancelled)
                assertTrue(System.nanoTime() - started < 300_000_000, "the waiter left at once")
                assertEquals(AccessToken("lgr_at_1"), tokens.token())
                assertEquals(1, server.hits("/oauth/token"))
            }
        }

    /**
     * 29.9.26s AC7: with tokenRequestTimeout at 300 ms, a token endpoint that stalls for 2 s fails
     * that attempt with TransportException TIMEOUT for every waiter, even when the waiters run
     * under runTest; nothing is cached, and the next token() starts a fresh exchange.
     */
    @Test
    fun tokenRequestTimeoutBoundsEachAttempt() =
        runTest {
            val stall: Handler = { exchange ->
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, 100)
                Fakes.sleep(2000)
                throw IOException("the stall is over")
            }
            FakeServer().on("/oauth/token", stall).use { server ->
                val tokens = source(server, 300.milliseconds)
                val got = together(2, coroutineContext) { tokens.token() }.map { it.exceptionOrNull() }
                got.forEach { assertEquals(TransportKind.TIMEOUT, assertIs<TransportException>(it).kind) }
                assertFailsWith<TransportException> { tokens.token() }
                assertEquals(2, server.hits("/oauth/token"))
            }
        }
}
