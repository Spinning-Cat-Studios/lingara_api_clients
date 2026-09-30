package com.getlingara.kotlin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class RetryTest {
    private val clock = SettableClock()
    private val recorder = RecordingSleeper()

    private fun client(server: FakeServer): LingaraClient =
        LingaraClient {
            baseUrl = server.uri()
            clock = this@RetryTest.clock
            sleeper = recorder.sleeper
        }

    private fun usage(vararg script: Handler): FakeServer = FakeServer().on("/v1/usage", Fakes.script(*script))

    /**
     * 29.9.26s AC8: a Retry-After above retryAfterCap throws at once with retryAfter set; a missing
     * one throws at once; an HTTP-date is read against the Clock; three 429s throw after two sleeps.
     */
    @Test
    fun retryAfterCapMissingHeaderDateAndExhaustion() =
        runBlocking {
            usage(Fakes.status(429, "Retry-After", "120", LIMITED)).use { server ->
                val e = assertFailsWith<ApiException> { client(server).getUsage() }
                assertEquals(120.seconds, e.retryAfter)
                assertEquals(1, server.hits("/v1/usage"))
            }
            usage(Fakes.status(429, null, null, LIMITED)).use { server ->
                assertNull(assertFailsWith<ApiException> { client(server).getUsage() }.retryAfter)
                assertEquals(1, server.hits("/v1/usage"))
            }
            assertEquals(listOf(), recorder.sleeps)
            val inSeven = DateTimeFormatter.RFC_1123_DATE_TIME.format(clock.instant().plusSeconds(7).atOffset(ZoneOffset.UTC))
            usage(Fakes.status(503, "Retry-After", inSeven, LIMITED), Fakes.status(200, null, null, OK)).use { server ->
                client(server).getUsage()
                assertEquals(listOf(7.seconds), recorder.sleeps)
            }
            recorder.sleeps.clear()
            usage(Fakes.status(429, "Retry-After", "1", LIMITED)).use { server ->
                assertFailsWith<ApiException> { client(server).getUsage() }
                assertEquals(3, server.hits("/v1/usage"))
                assertEquals(listOf(1.seconds, 1.seconds), recorder.sleeps)
            }
        }

    /**
     * 29.9.26s AC9: cancelling the caller during a Retry-After wait throws CancellationException,
     * not a LingaraException, and no further request is made.
     */
    @Test
    fun cancelDuringBackoffIsCancellation() =
        runBlocking {
            usage(Fakes.status(429, "Retry-After", "30", LIMITED)).use { server ->
                val client = LingaraClient { baseUrl = server.uri() }
                val thrown = CompletableDeferred<Throwable>()
                val caller =
                    launch(Dispatchers.Default) {
                        try {
                            client.getUsage()
                        } catch (e: Throwable) {
                            thrown.complete(e)
                            throw e
                        }
                    }
                delay(300)
                caller.cancel()
                assertIs<CancellationException>(withTimeout(5.seconds) { thrown.await() })
                delay(100)
                assertEquals(1, server.hits("/v1/usage"))
            }
        }

    private companion object {
        const val OK = """{"allowance":[]}"""
        const val LIMITED = """{"code":"rate_limited","error":"Slow down."}"""
    }
}
