<?php

declare(strict_types=1);

namespace Lingara\Internal;

use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Psr\Http\Client\ClientExceptionInterface;
use Psr\Http\Client\ClientInterface;
use Psr\Http\Message\RequestFactoryInterface;
use Psr\Http\Message\RequestInterface;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\StreamFactoryInterface;
use Psr\Http\Message\StreamInterface;

/**
 * One HttpStack's two clients and its factories, and the two places a
 * request can fail: sendRequest() and a body read (D3, D4).
 *
 * The library keeps its own read deadline. A read that returns '' without
 * eof() is "no data yet", as Guzzle's StreamHandler returns when its
 * read_timeout expires, never EOF: the loop reads again until the deadline
 * has passed with a read pending and no byte. So the timer runs only while a
 * read is pending, and the stack's own timeout can only make the library read
 * more often, never time out early. `Timeout` is judged by that elapsed time
 * on hrtime(), not by any client's exception class.
 *
 * @internal
 */
final class Transport
{
    private const CHUNK = 8192;

    /**
     * @param array<string, mixed> $v1Options    how a built /v1 client was configured
     * @param array<string, mixed> $tokenOptions how a built token client was configured
     */
    public function __construct(
        private readonly ClientInterface $v1,
        private readonly ClientInterface $token,
        private readonly RequestFactoryInterface $requests,
        private readonly StreamFactoryInterface $streams,
        public readonly bool $built,
        public readonly array $v1Options = [],
        public readonly array $tokenOptions = [],
    ) {}

    public function request(string $method, string $url): RequestInterface
    {
        return $this->requests->createRequest($method, $url);
    }

    public function body(string $text): StreamInterface
    {
        return $this->streams->createStream($text);
    }

    public function v1Client(): ClientInterface
    {
        return $this->v1;
    }

    public function tokenClient(): ClientInterface
    {
        return $this->token;
    }

    /**
     * Sends one request and returns its response once the headers are in.
     *
     * @param float        $bound   the seconds after which a failure is Timeout
     * @param list<string> $secrets scrubbed from any cause
     *
     * @throws TransportException
     */
    public function send(
        #[\SensitiveParameter]
        ClientInterface $client,
        #[\SensitiveParameter]
        RequestInterface $request,
        float $bound,
        #[\SensitiveParameter]
        array $secrets,
    ): ResponseInterface {
        $started = self::now();
        try {
            return $client->sendRequest($request);
        } catch (ClientExceptionInterface $e) {
            $kind = ErrorMapper::sendKind($e, self::since($started), $bound);
            throw new TransportException($kind, 'the request failed', ErrorMapper::scrub($e, $secrets));
        }
    }

    /**
     * The next bytes of $body, or '' at its end. A read pending with no byte
     * for $idle seconds is Timeout; with $deadline (hrtime nanoseconds) the
     * whole read must end by then instead, as a token exchange must.
     *
     * @param list<string> $secrets
     *
     * @throws TransportException
     */
    public function read(
        #[\SensitiveParameter]
        StreamInterface $body,
        float $idle,
        #[\SensitiveParameter]
        array $secrets,
        ?int $deadline = null,
    ): string {
        $until = $deadline ?? self::now() + (int) ($idle * 1e9);
        while (true) {
            try {
                $bytes = $body->read(self::CHUNK);
            } catch (\Throwable $e) {
                // StreamHandler throws, rather than returning '', when its
                // read_timeout expires: that is "no data yet" too.
                $bytes = self::timedOut($body) ? '' : throw new TransportException(
                    self::now() >= $until ? TransportKind::Timeout : TransportKind::Reset,
                    'reading the response failed',
                    ErrorMapper::scrub($e, $secrets),
                );
            }
            if ($bytes !== '') {
                return $bytes;
            }
            if ($body->eof()) {
                return '';
            }
            if (self::now() >= $until) {
                throw new TransportException(TransportKind::Timeout, 'no byte arrived before the timeout');
            }
            usleep(1000);
        }
    }

    /**
     * A whole body, through read()'s pending-read loop: never getContents()
     * or a (string) cast, which on StreamHandler return a silently truncated
     * body once a read times out.
     *
     * @param list<string> $secrets
     *
     * @throws TransportException
     */
    public function readAll(
        #[\SensitiveParameter]
        StreamInterface $body,
        float $idle,
        #[\SensitiveParameter]
        array $secrets,
        ?int $deadline = null,
    ): string {
        $text = '';
        try {
            while (($bytes = $this->read($body, $idle, $secrets, $deadline)) !== '') {
                $text .= $bytes;
            }
        } finally {
            $body->close();
        }
        return $text;
    }

    private static function timedOut(StreamInterface $body): bool
    {
        try {
            return $body->getMetadata('timed_out') === true && !$body->eof();
        } catch (\Throwable) {
            return false;
        }
    }

    /** Monotonic nanoseconds: real time, never the clock seam, because the timeouts are real. */
    public static function now(): int
    {
        return (int) hrtime(true);
    }

    public static function since(int $started): float
    {
        return (self::now() - $started) / 1e9;
    }
}
