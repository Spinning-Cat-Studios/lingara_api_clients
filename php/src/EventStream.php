<?php

declare(strict_types=1);

namespace Lingara;

use Lingara\Exception\ApiException;
use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\Internal\Frame;
use Lingara\Internal\Json;
use Lingara\Internal\Operations;
use Lingara\Internal\Secrets;
use Lingara\Internal\SseDecoder;
use Lingara\Internal\Transport;
use Psr\Http\Message\StreamInterface;

/**
 * K5: one stream of events (CONTRACT.md K5; D3, D6).
 *
 *     foreach ($client->generateVocabulary($request) as $event) {
 *         if ($event instanceof GenerateVocabularyEvent\Item) { … }
 *     }
 *
 * The call that returned it has already sent the request and read the
 * headers, so a refusal was thrown there. Iterating reads the body; an
 * in-stream failure, or the stream's `error` event, is thrown from inside
 * the `foreach`. It may be iterated once: a second getIterator() throws
 * \LogicException, because the body has already been read.
 *
 * Leaving the `foreach` by any path (break, return, an exception from the
 * loop body) closes the connection: the stream keeps no reference to its own
 * generator, so leaving drops its last one, and the generator's `finally`
 * closes the body. close() and the destructor do the same, so a stream that
 * is never iterated holds its connection only until then; in a long-lived
 * process, call close() in a `finally`.
 *
 * @implements \IteratorAggregate<int, object>
 */
final class EventStream implements \IteratorAggregate
{
    private bool $iterated = false;
    private bool $closed = false;
    /** Whether the body ended with no close(): K5's "ended early" when no ending came first. */
    private bool $eof = false;

    /**
     * Keys the body, the transport and the token in Secrets: the body and the
     * HTTP client behind it may hold the request, Authorization included.
     */
    private readonly object $handle;

    /**
     * $token is the one the request carried, kept only to scrub a read
     * failure's cause.
     *
     * @internal built by Client
     */
    public function __construct(
        StreamInterface $body,
        private readonly string $operationId,
        private readonly ?string $servedVersion,
        Transport $transport,
        private readonly float $idleTimeout,
        ?AccessToken $token = null,
    ) {
        $this->handle = Secrets::handle();
        Secrets::put($this->handle, 'body', $body);
        Secrets::put($this->handle, 'transport', $transport);
        Secrets::put($this->handle, 'token', $token);
    }

    /** The Lingara-Version echo, or null when the server sent none. */
    public function servedVersion(): ?string
    {
        return $this->servedVersion;
    }

    /** Closes the connection. Idempotent; iteration after it yields nothing more. */
    public function close(): void
    {
        if (!$this->closed) {
            $this->closed = true;
            // At shutdown the store may already be gone: nothing is left to close.
            $body = Secrets::get($this->handle, 'body');
            if ($body instanceof StreamInterface) {
                $body->close();
            }
        }
    }

    /** @throws \LogicException on a second call */
    public function getIterator(): \Generator
    {
        $this->claim();
        return $this->events();
    }

    /**
     * The raw frames, each with its last-event-id, for K5a's tail (ADR
     * 30.9.26aa D7), which reads `id` and `done` where K5 hides them. It ends
     * at EOF, or once close() is called; the body is closed when it ends.
     *
     * @return \Generator<int, Frame>
     *
     * @throws \LogicException after getIterator() or a first frames()
     *
     * @internal read by EventTail
     */
    public function frames(): \Generator
    {
        $this->claim();
        return $this->read();
    }

    public function __destruct()
    {
        $this->close();
    }

    /** @return array<string, mixed> */
    public function __debugInfo(): array
    {
        return ['operationId' => $this->operationId, 'servedVersion' => $this->servedVersion, 'closed' => $this->closed];
    }

    /** @return \Generator<int, object> */
    private function events(): \Generator
    {
        foreach ($this->read() as $frame) {
            $event = $this->decode($frame);
            if ($event !== null) {
                yield $event;
            }
            if ($this->ends($frame)) {
                return;
            }
        }
        if ($this->eof) {
            throw new TransportException(TransportKind::StreamEndedEarly, 'the stream closed before its terminal event');
        }
    }

    /** @return \Generator<int, Frame> */
    private function read(): \Generator
    {
        try {
            $decoder = new SseDecoder();
            $transport = Secrets::get($this->handle, 'transport');
            $token = Secrets::get($this->handle, 'token');
            if (!$transport instanceof Transport) {
                throw new \LogicException('the stream has no transport');
            }
            $secrets = Client::secrets($token instanceof AccessToken ? $token : null);
            while (!$this->closed && ($bytes = $transport->read($this->body(), $this->idleTimeout, $secrets)) !== '') {
                foreach ($decoder->feed($bytes) as $frame) {
                    yield $frame;
                }
            }
            $this->eof = !$this->closed;
        } finally {
            $this->close();
        }
    }

    private function claim(): void
    {
        if ($this->iterated) {
            throw new \LogicException('an EventStream can be iterated once: its body has already been read');
        }
        $this->iterated = true;
    }

    /**
     * The event to yield for a frame, or null: an unknown event is skipped
     * and a Done payload ends the stream unyielded. An `error` event throws.
     */
    private function decode(Frame $frame): ?object
    {
        $event = $this->event($frame->event);
        if ($event === null) {
            return null;
        }
        try {
            $data = Json::decode($frame->data);
        } catch (\JsonException) {
            throw new TransportException(TransportKind::MalformedEvent, "{$frame->event}: data is not JSON");
        }
        if ($event['end'] === 'raise') {
            throw self::streamError($data, $this->servedVersion);
        }
        return $event['end'] === 'quiet' ? null : Operations::decode($this->operationId, $frame->event, $data);
    }

    private function body(): StreamInterface
    {
        $body = Secrets::get($this->handle, 'body');
        return $body instanceof StreamInterface ? $body : throw new \LogicException('the stream has no body');
    }

    private function ends(Frame $frame): bool
    {
        return ($this->event($frame->event)['end'] ?? null) !== null;
    }

    /** @return array{class: class-string|null, data: class-string|null, end: string|null}|null */
    private function event(string $name): ?array
    {
        return Operations::OPERATIONS[$this->operationId]['stream']['events'][$name] ?? null;
    }

    /**
     * An `error` event: ApiException with status 200, never yielded or retried.
     *
     * @internal EventTail raises the same exception for its last failure
     */
    public static function streamError(mixed $data, ?string $servedVersion): ApiException
    {
        $text = static fn(string $key): ?string => $data instanceof \stdClass && is_string($data->{$key} ?? null)
            ? $data->{$key}
            : null;
        return new ApiException(
            200,
            $text('code') ?? 'stream_error',
            $text('message') ?? 'the stream reported an error',
            null,
            $text('plan_id'),
            $servedVersion,
        );
    }
}
