package com.getlingara.kotlin

import com.getlingara.kotlin.internal.Streams
import com.getlingara.kotlin.model.CreateLessonPlanEvent
import com.getlingara.kotlin.model.GenerateVocabularyEvent
import com.getlingara.kotlin.model.StreamLessonPlanEvent
import com.getlingara.kotlin.model.VocabRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EventStreamTest {
    private fun vocab(
        body: InputStream,
        idle: Duration = 120.seconds,
    ): EventStream<GenerateVocabularyEvent> = EventStream(Streams.GENERATE_VOCABULARY, body, idle, PIN)

    private suspend fun <E : Any> drain(stream: EventStream<E>): List<E> = stream.use { it.toList() }

    /** A server that sends one started frame and then holds, counting down when the client hangs up. */
    private fun holding(hungUp: CountDownLatch): Listener =
        Listener {
            Listener.readRequest(it)
            val size = STARTED.toByteArray().size
            Listener.write(
                it,
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    "${Integer.toHexString(size)}\r\n$STARTED\r\n",
            )
            Listener.awaitHangUp(it)
            hungUp.countDown()
        }

    /**
     * 29.9.26s AC14: cancelling the collector mid-stream closes the connection, which a local
     * listener sees within 2 s; the CancellationException is not wrapped; and a second collect
     * throws IllegalStateException.
     */
    @Test
    fun cancelClosesTheConnectionAndTheStreamIsSingleCollect(): Unit =
        runBlocking {
            val hungUp = CountDownLatch(1)
            holding(hungUp).use { server ->
                val stream = LingaraClient { baseUrl = server.uri("http") }.generateVocabulary(VocabRequest(2, "en", "zh"))
                val first = CompletableDeferred<GenerateVocabularyEvent>()
                val thrown = CompletableDeferred<Throwable>()
                val collector =
                    launch(Dispatchers.Default) {
                        try {
                            stream.collect { first.complete(it) }
                        } catch (e: Throwable) {
                            thrown.complete(e)
                            throw e
                        }
                    }
                assertIs<GenerateVocabularyEvent.Started>(withTimeout(2.seconds) { first.await() })
                collector.cancel()
                assertTrue(hungUp.await(2, TimeUnit.SECONDS), "the server saw no disconnect within 2 s")
                assertIs<CancellationException>(withTimeout(2.seconds) { thrown.await() })
                assertFailsWith<IllegalStateException> { stream.collect {} }
            }
        }

    /**
     * 29.9.26s AC15: close() from another coroutine mid-collect ends the collection normally with
     * no further event, whether the blocked read throws or returns EOF, and use {} on a stream that
     * is never collected closes its body and cancels its watchdog.
     */
    @Test
    fun closeEndsCollectionAndUseReleasesAnUncollectedStream() =
        runBlocking {
            for (ending in Ending.entries) {
                val body = ScriptedBody(ending, STARTED, ScriptedBody.BLOCK)
                val stream = vocab(body)
                val events = mutableListOf<GenerateVocabularyEvent>()
                val collector = launch(Dispatchers.Default) { stream.collect { events.add(it) } }
                assertTrue(body.blocked.await(2, TimeUnit.SECONDS))
                stream.close()
                withTimeout(2.seconds) { collector.join() }
                assertFalse(collector.isCancelled, "$ending: the collection ended normally")
                assertEquals(1, events.size, ending.name)
            }
            val untouched = ScriptedBody(STARTED)
            val stream = vocab(untouched)
            stream.use { }
            assertTrue(untouched.closed)
            assertFalse(stream.watchdog.isActive)
            assertEquals(0, untouched.reads.get())
        }

    /**
     * 29.9.26s AC16: with streamIdleTimeout at 500 ms, a body silent for 1 s while a read is
     * pending fails with TransportException TIMEOUT when collected under runTest, a keepalive every
     * 50 ms keeps it open, a collector that holds one event inside emit for 1 s then returns
     * receives the next event with no error, a caller's cancel still ends it with
     * CancellationException, and the stream's internal watchdog Job is no longer active after the
     * stream ends.
     */
    @Test
    fun idleTimeoutIsRealTimeAndAKeepaliveResetsIt() =
        runTest {
            val idle = 500.milliseconds
            for (ending in Ending.entries) {
                val silent = vocab(ScriptedBody(ending, STARTED, ScriptedBody.BLOCK), idle)
                val e = assertFailsWith<TransportException> { drain(silent) }
                assertEquals(TransportKind.TIMEOUT, e.kind, ending.name)
                assertFalse(silent.watchdog.isActive)
            }
            val keptAlive = (1..20).flatMap { listOf<Any>(50L, ": keepalive\n\n") } + listOf(STARTED, DONE)
            val alive = vocab(ScriptedBody(*keptAlive.toTypedArray()), idle)
            assertEquals(1, drain(alive).size)
            assertFalse(alive.watchdog.isActive, "the watchdog is cancelled when the stream ends")
            val held = vocab(ScriptedBody(STARTED, ITEM, DONE), idle)
            val got = mutableListOf<GenerateVocabularyEvent>()
            held.collect {
                got.add(it)
                if (got.size == 1) Thread.sleep(1000)
            }
            assertIs<GenerateVocabularyEvent.Item>(got[1])
            assertFalse(held.watchdog.isActive)
            val blocked = ScriptedBody(Ending.THROW, STARTED, ScriptedBody.BLOCK)
            val cancelled = vocab(blocked, idle)
            val collector = launch(Dispatchers.Default) { cancelled.collect { } }
            assertTrue(blocked.blocked.await(2, TimeUnit.SECONDS))
            collector.cancel()
            collector.join()
            assertTrue(collector.isCancelled)
            assertFalse(cancelled.watchdog.isActive)
        }

    private suspend fun <E : Any> run(
        route: Streams.Route<E>,
        vararg body: Any,
    ): List<E> = drain(EventStream(route, ScriptedBody(*body), 120.seconds, null))

    /**
     * 29.9.26s AC17: each stream operation ends on its own C2 D6 terminal (result and pending
     * emitted, done not); every Streams route has a method and a terminal row, every terminal row
     * names a route, and every terminal is among its route's event names — the table itself being
     * the view's endsOn since ADR 29.9.26ai.
     */
    @Test
    fun eachOperationEndsOnItsOwnTerminal() =
        runBlocking {
            assertEquals(2, run(Streams.GENERATE_VOCABULARY, STARTED, ITEM, DONE).size)
            val created = run(Streams.CREATE_LESSON_PLAN, PLAN_STARTED, PHASE, "event: result\ndata: $RESULT\n\n", ScriptedBody.FAIL)
            assertIs<CreateLessonPlanEvent.Result>(created[2])
            val pending =
                run(
                    Streams.STREAM_LESSON_PLAN,
                    PLAN_STARTED,
                    "event: pending\ndata: {\"plan_id\":\"p\",\"status\":\"generating\"}\n\n",
                    ScriptedBody.FAIL,
                )
            assertIs<StreamLessonPlanEvent.Pending>(pending[1])
            val tutor = run(Streams.SEND_TUTOR_MESSAGE, "event: delta\ndata: {\"text\":\"你好\"}\n\n", DONE, ScriptedBody.FAIL)
            assertEquals(1, tutor.size)
            TerminalTable.assertIsTheView()
        }

    /**
     * 29.9.26s AC18: an error event throws ApiException into the collector with status 200, code,
     * message, planId and servedVersion; it is never emitted and never retried, and the body then
     * closes.
     */
    @Test
    fun anErrorEventThrowsApiExceptionWithPlanId() =
        runBlocking {
            val error = "event: error\ndata: {\"code\":\"generation_failed\",\"message\":\"It failed.\",\"plan_id\":\"p-1\"}\n\n"
            val body = ScriptedBody(STARTED, error, ScriptedBody.FAIL)
            val events = mutableListOf<GenerateVocabularyEvent>()
            val e = assertFailsWith<ApiException> { vocab(body).collect { events.add(it) } }
            assertEquals(200, e.status)
            assertEquals("generation_failed", e.code)
            assertEquals("It failed.", e.message)
            assertEquals("p-1", e.planId)
            assertEquals(PIN, e.servedVersion)
            assertNull(e.retryAfter)
            assertEquals(1, events.size)
            assertTrue(body.closed)
            assertFalse(body.readAfterFail)
        }

    private suspend fun failure(vararg body: Any): TransportKind =
        assertFailsWith<TransportException> {
            drain(vocab(ScriptedBody(*body)))
        }.kind

    /**
     * 29.9.26s AC19: a non-SSE 200 is MALFORMED_RESPONSE from the call; an unknown event is
     * skipped; a known event whose data has the wrong JSON type is MALFORMED_EVENT and is never
     * emitted; an extra field decodes; EOF before a terminal is STREAM_ENDED_EARLY; bytes after a
     * terminal are never read.
     */
    @Test
    fun theStreamEndsPerC2D6() =
        runBlocking {
            FakeServer().on("/v1/vocab/stream") { Fakes.json(it, 200, "{\"not\":\"sse\"}") }.use { server ->
                val client = LingaraClient { baseUrl = server.uri() }
                val e = assertFailsWith<TransportException> { client.generateVocabulary(VocabRequest(1, "en", "zh")) }
                assertEquals(TransportKind.MALFORMED_RESPONSE, e.kind)
            }
            assertEquals(1, drain(vocab(ScriptedBody("event: mystery\ndata: {}\n\n", STARTED, DONE))).size)
            assertEquals(TransportKind.MALFORMED_EVENT, failure("event: item\ndata: {not json\n\n"))
            assertEquals(TransportKind.MALFORMED_EVENT, failure("event: item\ndata: \"a string\"\n\n"))
            val extra = "event: item\ndata: {\"word\":\"a\",\"translation\":\"b\",\"added_later\":[1]}\n\n"
            assertEquals(1, drain(vocab(ScriptedBody(extra, DONE))).size)
            assertEquals(TransportKind.STREAM_ENDED_EARLY, failure(STARTED))
            val afterDone = ScriptedBody(STARTED + DONE, ScriptedBody.FAIL)
            assertEquals(1, drain(vocab(afterDone)).size)
            assertFalse(afterDone.readAfterFail)
            assertEquals(1, afterDone.reads.get())
        }

    companion object {
        const val PIN = "2026-09-knowing-tenpounder"
        const val STARTED =
            "event: started\ndata: {\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\"," +
                "\"framework\":\"HSK\",\"count\":2,\"ai_generated\":true}}\n\n"
        const val ITEM = "event: item\ndata: {\"word\":\"你好\",\"pronunciation\":\"nǐ hǎo\",\"translation\":\"hello\"}\n\n"
        const val DONE = "event: done\ndata: {}\n\n"
        const val PLAN_STARTED = "event: started\ndata: {\"plan_id\":\"3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37\"}\n\n"
        const val PHASE = "event: phase\ndata: {\"phase\":\"vocabulary\",\"attempt\":1}\n\n"
        const val RESULT =
            "{\"plan\":{\"id\":\"p\",\"status\":\"complete\",\"source_lang\":\"en\",\"target_lang\":\"zh\"," +
                "\"level\":2,\"created_at\":\"2026-09-29T00:00:00Z\",\"ai_generated\":true}}"
    }
}
