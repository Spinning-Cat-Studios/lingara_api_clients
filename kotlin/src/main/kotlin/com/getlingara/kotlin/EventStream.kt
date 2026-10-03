package com.getlingara.kotlin

import com.getlingara.kotlin.internal.ErrorMapper
import com.getlingara.kotlin.internal.LibraryScope
import com.getlingara.kotlin.internal.LingaraJson
import com.getlingara.kotlin.internal.SseDecoder
import com.getlingara.kotlin.internal.Streams
import com.getlingara.kotlin.internal.cancellableRead
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * One open stream of events (CONTRACT.md K5; ADR 29.9.26s D4, D8, D9): a [Flow] you collect once,
 * and a [Closeable] for a stream you never collect.
 *
 * The request was sent when the operation returned, so collecting never re-sends it and a second
 * `collect` throws `IllegalStateException`. An `error` event is thrown into the collector as an
 * [ApiException] with status 200, never emitted. A `Done`-bodied ending event ends the flow
 * unemitted; any other ending event is emitted, and then the flow completes. After a terminal
 * event the body is closed and no later byte is read. Collection closes the body however it ends.
 *
 * Cancelling the collector closes the connection and throws its own `CancellationException`. A
 * [close] from another coroutine ends the collection normally, with no further event.
 *
 * [cursor] is the `id:` of the last frame that carried one; only the events tail reads it, to resume
 * after an ending (CONTRACT.md K5a; ADR 30.9.26aa D7).
 */
public class EventStream<E : Any> internal constructor(
    private val route: Streams.Route<E>,
    private val body: InputStream,
    private val idle: Duration,
    /** The `Lingara-Version` the server answered under, when it sent one. */
    public val servedVersion: String?,
) : Flow<E>,
    Closeable {
    private val collected = AtomicBoolean()
    private val finished = AtomicBoolean()

    // Written by close() or the watchdog on another thread; read after a read ends (D8's flags).
    @Volatile private var closed = false

    @Volatile private var timedOut = false

    // The watchdog fires only while a read is pending: time the collector spends in emit never counts.
    @Volatile private var readPending = false

    @Volatile private var lastByteNanos = System.nanoTime()

    /** The `id:` of the last frame that carried one, or `null` before any did. */
    @Volatile
    public var cursor: String? = null
        private set

    /** One watchdog per stream, in the library's scope so its timer is real time (D9). */
    internal val watchdog: Job = LibraryScope.launch { watch() }

    private val events: Flow<E> =
        flow {
            try {
                val sse = SseDecoder()
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val frames = read(sse, buffer) ?: return@flow
                    for (frame in frames) {
                        val step = interpret(frame) ?: continue
                        if (step.last) finish()
                        step.event?.let { emit(it) }
                        if (step.last) return@flow
                    }
                }
            } finally {
                finish()
            }
        }

    /** Collects the events; a second call throws `IllegalStateException`. */
    override suspend fun collect(collector: FlowCollector<E>) {
        check(collected.compareAndSet(false, true)) { "a Lingara EventStream can be collected once" }
        events.collect(collector)
    }

    /** Ends the stream and closes the connection. Idempotent, and safe from any coroutine. */
    override fun close() {
        closed = true
        finish()
    }

    /** One read into the decoder, or `null` when a foreign [close] ended the stream. */
    private suspend fun read(
        sse: SseDecoder,
        buffer: ByteArray,
    ): List<SseDecoder.Frame>? {
        lastByteNanos = System.nanoTime()
        readPending = true
        val n =
            try {
                body.cancellableRead { read(buffer) }
            } catch (e: IOException) {
                return endedBy(e)
            } finally {
                readPending = false
            }
        if (n < 0) return endedBy(null)
        lastByteNanos = System.nanoTime()
        return sse.feed(buffer, n)
    }

    /**
     * The flag decides, not the way the read ended: an interrupted or foreign-closed body may throw
     * or return EOF depending on the JDK release (D8). Cancellation first, then the watchdog, then
     * a foreign close, which ends the collection normally.
     */
    private suspend fun endedBy(failure: IOException?): List<SseDecoder.Frame>? {
        currentCoroutineContext().ensureActive()
        if (timedOut) throw TransportException(TransportKind.TIMEOUT, failure)
        if (closed) return null
        if (failure == null) throw TransportException(TransportKind.STREAM_ENDED_EARLY, null)
        throw ErrorMapper.transport(failure, true, emptyList())
    }

    /** What one frame means: skip it (`null`), emit it, end quietly, or throw. */
    private fun interpret(frame: SseDecoder.Frame): Step<E>? {
        if (frame.id.isNotEmpty()) cursor = frame.id
        if (frame.event !in route.events) return null
        val data =
            try {
                LingaraJson.parseToJsonElement(frame.data)
            } catch (e: IllegalArgumentException) {
                throw TransportException(TransportKind.MALFORMED_EVENT, e)
            }
        val ending = route.endsOn[frame.event]
        if (ending == Streams.Ending.RAISE) throw streamError(data)
        if (ending == Streams.Ending.QUIET) return Step(null, true)
        val event =
            try {
                route.decode(frame.event, data, LingaraJson)
            } catch (e: IllegalArgumentException) {
                throw TransportException(TransportKind.MALFORMED_EVENT, e)
            }
        return event?.let { Step(it, ending == Streams.Ending.YIELD) }
    }

    private fun streamError(data: JsonElement): ApiException =
        ApiException(
            status = 200,
            code = ErrorMapper.text(data, "code") ?: "stream_error",
            message = ErrorMapper.text(data, "message") ?: "the stream reported an error",
            retryAfter = null,
            planId = ErrorMapper.text(data, "plan_id"),
            servedVersion = servedVersion,
        )

    /** An event to hand over, or `null` for a quiet end, and whether the stream ends with it. */
    private class Step<E>(
        val event: E?,
        val last: Boolean,
    )

    /** Closes the body and cancels the watchdog: every exit path runs this. */
    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        watchdog.cancel()
        try {
            body.close()
        } catch (e: IOException) {
            // Nothing to do: the stream is over either way.
        }
    }

    /**
     * With a read pending and no byte for the timeout, marks the stream timed out and closes the
     * body, so the blocked read ends; with none pending, waits a full timeout again.
     */
    private suspend fun watch() {
        val idleNanos = idle.inWholeNanoseconds
        while (!finished.get()) {
            val remaining = idleNanos - (System.nanoTime() - lastByteNanos)
            when {
                !readPending -> delay(idle)
                remaining > 0 -> delay(remaining.nanoseconds)
                else -> {
                    timedOut = true
                    finish()
                }
            }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
    }
}
