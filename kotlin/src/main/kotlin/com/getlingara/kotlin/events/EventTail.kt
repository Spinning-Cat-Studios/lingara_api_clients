package com.getlingara.kotlin.events

import com.getlingara.kotlin.ApiException
import com.getlingara.kotlin.EventStream
import com.getlingara.kotlin.LingaraException
import com.getlingara.kotlin.MaintenanceException
import com.getlingara.kotlin.TransportException
import com.getlingara.kotlin.TransportKind
import com.getlingara.kotlin.internal.Policy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The tail helper, `client.tailEvents(…)`: the event stream, reopened after every ending, as a
 * [Flow] that never completes on its own (CONTRACT.md K5a; ADR 30.9.26aa D7). Collect it once.
 *
 * A `done` moves [cursor] and reopens at once. An `error` event, an end of the body, and every
 * transport failure, the idle timeout included, are failures: the tail sleeps 1 s, doubling up to
 * 30 s, and reopens from [cursor] with `Last-Event-ID`. The first open counts too. The count
 * resets on the first `event` or `done` a connection delivers; after `tailMaxFailures` in a row
 * (8, about 91 s of sleeps) the last failure is thrown. A `429` or `503` is one failure whose
 * `Retry-After`, within the cap, replaces that step's delay; every other refusal is thrown at once,
 * `410 cursor_expired` included. Cancelling the collector ends it, during a reconnect sleep too.
 */
public class EventTail internal constructor(
    cursor: String?,
    private val policy: Policy,
    private val maxFailures: Int,
    private val open: suspend (String?) -> EventStream<Event>,
) : Flow<Event> {
    private val collected = AtomicBoolean()
    private var failures = 0

    /**
     * The `id:` of the last `event` or `done` seen, or the caller's cursor before any: a game that
     * saves it resumes with no gap, by `tailEvents` or by `events`.
     */
    @Volatile
    public var cursor: String? = cursor
        private set

    /** Collects the events until cancelled or until the failures exceed the bound. */
    override suspend fun collect(collector: FlowCollector<Event>) {
        check(collected.compareAndSet(false, true)) { "a Lingara EventTail can be collected once" }
        while (true) {
            val stream = openOrNull() ?: continue
            drain(stream, collector)
        }
    }

    /** One open; `null` when it failed and its backoff has been slept. */
    private suspend fun openOrNull(): EventStream<Event>? {
        try {
            return open(cursor)
        } catch (e: ApiException) {
            if (e.status != 429 && e.status != 503) throw e
            failed(e, e.retryAfter)
        } catch (e: MaintenanceException) {
            failed(e, e.retryAfter)
        } catch (e: TransportException) {
            failed(e, null)
        }
        return null
    }

    /** Emits one connection's events; returns when it ended, having counted any failure. */
    private suspend fun drain(
        stream: EventStream<Event>,
        collector: FlowCollector<Event>,
    ) {
        try {
            stream.collect { event ->
                moved(stream)
                try {
                    collector.emit(event)
                } catch (e: Throwable) {
                    throw Downstream(e)
                }
            }
            // A done: its id is the horizon, and the reopen is immediate and not a failure.
            moved(stream)
        } catch (e: Downstream) {
            throw e.failure
        } catch (e: ApiException) {
            failed(e, null)
        } catch (e: TransportException) {
            // A known type whose data does not decode: a reopen from the same cursor would meet it.
            if (e.kind == TransportKind.MALFORMED_EVENT) throw e
            failed(e, null)
        } finally {
            stream.close()
        }
    }

    private fun moved(stream: EventStream<Event>) {
        failures = 0
        stream.cursor?.let { cursor = it }
    }

    /**
     * Counts one failure, throwing it when the bound is spent or its `Retry-After` is above the cap,
     * and otherwise sleeps that step's delay.
     */
    private suspend fun failed(
        failure: LingaraException,
        retryAfter: Duration?,
    ) {
        if (retryAfter != null && retryAfter > policy.cap) throw failure
        failures++
        if (failures >= maxFailures) throw failure
        policy.sleeper(retryAfter ?: minOf(MAX_DELAY_SECONDS, 1L shl minOf(failures - 1, 5)).seconds)
    }

    /** What the collector threw, carried through the connection's own flow unchanged. */
    private class Downstream(
        val failure: Throwable,
    ) : RuntimeException(null, null, false, false)

    private companion object {
        const val MAX_DELAY_SECONDS = 30L
    }
}
