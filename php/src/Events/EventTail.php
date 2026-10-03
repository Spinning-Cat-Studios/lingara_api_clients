<?php

declare(strict_types=1);

namespace Lingara\Events;

use Lingara\EventStream;
use Lingara\Events\Generated\EventParser;
use Lingara\Exception\ApiException;
use Lingara\Exception\LingaraException;
use Lingara\Exception\MaintenanceException;
use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\Internal\Frame;
use Lingara\Internal\Json;

/**
 * K5a: the events stream as a tail that resumes (CONTRACT.md K5a; ADR
 * 30.9.26aa D7). Built by Client::tailEvents().
 *
 *     $tail = $client->tailEvents(cursor: $saved);
 *     foreach ($tail as $event) {
 *         handle($event);
 *         $saved = $tail->cursor();
 *     }
 *
 * It never ends on its own. A `done` moves cursor() and reopens at once; an
 * `error` event, EOF, the idle timeout or any TransportException is a failed
 * reopen, retried after 1, 2, 4, 8, 16, 30, 30 s through the sleeper seam,
 * with Last-Event-ID: cursor(). The count resets on a connection's first
 * `event` or `done` frame, never on its 200. After maxFailures consecutive
 * failures (the client's tailMaxFailures, default 8) the last is thrown. A
 * 401 refreshes the token once; a 429 or 503 is one failure, its
 * Retry-After within the cap replacing that step's delay; every other
 * refusal, a 410 cursor_expired included, is thrown at once. An open
 * bypasses K4's retries: this count is the only budget.
 *
 * Leaving the `foreach` closes the connection. It may be iterated once.
 *
 * @implements \IteratorAggregate<int, Event>
 */
final class EventTail implements \IteratorAggregate
{
    private const MAX_DELAY = 30;

    private bool $iterated = false;
    private int $failures = 0;

    /**
     * @param \Closure(?string): EventStream $open        one open, Last-Event-ID as given
     * @param \Closure(float): void          $sleeper
     *
     * @internal built by Client
     */
    public function __construct(
        private readonly \Closure $open,
        private ?string $cursor,
        private readonly \Closure $sleeper,
        private readonly int $maxFailures,
        private readonly float $retryAfterCap,
    ) {}

    /** The id of the last `event` or `done` frame that carried one, else the cursor the tail was opened with. */
    public function cursor(): ?string
    {
        return $this->cursor;
    }

    /** @throws \LogicException on a second call */
    public function getIterator(): \Generator
    {
        if ($this->iterated) {
            throw new \LogicException('an EventTail can be iterated once');
        }
        $this->iterated = true;
        return $this->tail();
    }

    /** @return \Generator<int, Event> */
    private function tail(): \Generator
    {
        while (true) {
            try {
                yield from $this->connection(($this->open)($this->cursor));
                continue;
            } catch (TransportException $e) {
                if ($e->kind() === TransportKind::MalformedEvent) {
                    throw $e;
                }
                $failure = $e;
                $wait = null;
            } catch (ApiException | MaintenanceException $e) {
                $failure = $e;
                $wait = $this->retryAfter($e);
            }
            $this->fail($failure, $wait);
        }
    }

    /**
     * One connection: yields its events and returns on `done`; an `error`
     * event or EOF is thrown as the failure it is.
     *
     * @return \Generator<int, Event>
     */
    private function connection(EventStream $stream): \Generator
    {
        foreach ($stream->frames() as $frame) {
            if ($frame->event === 'error') {
                throw EventStream::streamError(self::json($frame), $stream->servedVersion());
            }
            if ($frame->event !== 'event' && $frame->event !== 'done') {
                continue;
            }
            $this->failures = 0;
            $this->cursor = $frame->id === null || $frame->id === '' ? $this->cursor : $frame->id;
            if ($frame->event === 'done') {
                return;
            }
            yield self::event($frame);
        }
        throw new TransportException(TransportKind::StreamEndedEarly, 'the tail closed with no done');
    }

    /**
     * Seconds a 429 or 503 asked for, or null for the backoff delay. Any
     * other refusal, or a Retry-After above the cap, is thrown. A status-200
     * ApiException is an `error` event: a failure with the backoff delay.
     */
    private function retryAfter(ApiException|MaintenanceException $e): ?int
    {
        $status = $e instanceof ApiException ? $e->status() : 503;
        if ($status === 200) {
            return null;
        }
        if (($status !== 429 && $status !== 503) || $e->retryAfter() > $this->retryAfterCap) {
            throw $e;
        }
        return $e->retryAfter();
    }

    /** Counts one failure: throws it when the bound is spent, else sleeps that step's delay. */
    private function fail(LingaraException $failure, ?int $wait): void
    {
        $this->failures++;
        if ($this->failures >= $this->maxFailures) {
            throw $failure;
        }
        ($this->sleeper)((float) ($wait ?? min(self::MAX_DELAY, 2 ** ($this->failures - 1))));
    }

    private static function json(Frame $frame): mixed
    {
        try {
            return Json::decode($frame->data);
        } catch (\JsonException) {
            throw new TransportException(TransportKind::MalformedEvent, "{$frame->event}: data is not JSON");
        }
    }

    /** An `event` frame's envelope; a known type whose data does not decode is MalformedEvent. */
    private static function event(Frame $frame): Event
    {
        try {
            return EventParser::fromValue(self::json($frame));
        } catch (\UnexpectedValueException $e) {
            throw new TransportException(TransportKind::MalformedEvent, "event: {$e->getMessage()}");
        }
    }
}
