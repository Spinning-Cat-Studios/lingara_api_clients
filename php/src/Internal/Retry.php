<?php

declare(strict_types=1);

namespace Lingara\Internal;

use Psr\Clock\ClockInterface;
use Psr\Http\Message\ResponseInterface;

/**
 * K4's Retry-After loop around one HTTP request (CONTRACT.md K4).
 *
 * Each HTTP request has its own budget of maxAttempts: the token exchange is
 * one request and the /v1 request another, and Client's one 401 retry runs
 * the /v1 request again with a fresh budget. Every decision is made on the
 * status line and headers alone, before any body byte reaches the caller.
 *
 * @internal
 */
final class Retry
{
    /** A delta above this is far past any cap; clamping keeps it an int. */
    private const MAX_DELTA = 4294967296;

    /** Spelled out: PHP 8.5 deprecates DATE_RFC7231. */
    private const IMF_FIXDATE = 'D, d M Y H:i:s \G\M\T';

    /** @var \Closure(float): void */
    private readonly \Closure $sleeper;

    /** @param callable(float): void $sleeper */
    public function __construct(
        public readonly int $maxAttempts,
        public readonly float $retryAfterCap,
        private readonly ClockInterface $clock,
        callable $sleeper,
    ) {
        if ($maxAttempts < 1) {
            throw new \InvalidArgumentException('maxAttempts must be at least 1');
        }
        if ($retryAfterCap < 0) {
            throw new \InvalidArgumentException('retryAfterCap must not be negative');
        }
        $this->sleeper = \Closure::fromCallable($sleeper);
    }

    public function now(): \DateTimeImmutable
    {
        return $this->clock->now();
    }

    /**
     * Retry-After as whole seconds: delta-seconds, or an HTTP-date read
     * against $now (max(0, date − now), rounded up). Absent or unreadable is
     * null.
     */
    public static function parseRetryAfter(string $value, \DateTimeImmutable $now): ?int
    {
        $value = trim($value);
        if ($value === '') {
            return null;
        }
        if (ctype_digit($value)) {
            return strlen($value) > 10 ? self::MAX_DELTA : min((int) $value, self::MAX_DELTA);
        }
        $at = self::httpDate($value);
        if ($at === null) {
            return null;
        }
        $seconds = (float) $at->format('U') - (float) $now->format('U.u');
        return max(0, (int) ceil($seconds));
    }

    /** An IMF-fixdate (RFC 9110 §5.6.7), the one HTTP-date form a sender may generate, or null. */
    public static function httpDate(string $value): ?\DateTimeImmutable
    {
        $value = trim($value);
        $at = \DateTimeImmutable::createFromFormat('!' . self::IMF_FIXDATE, $value, new \DateTimeZone('UTC'));
        return $at === false || $at->format(self::IMF_FIXDATE) !== $value ? null : $at;
    }

    /**
     * Runs $attempt until it answers something other than a retryable 429 or
     * 503, or the attempts run out, and returns the last response. A
     * TransportException is never retried: it propagates.
     *
     * @param callable(): ResponseInterface $attempt
     */
    public function run(callable $attempt): ResponseInterface
    {
        for ($tries = 1; ; $tries++) {
            $response = $attempt();
            $wait = $this->waitFor($response, $tries);
            if ($wait === null) {
                return $response;
            }
            $response->getBody()->close();
            ($this->sleeper)((float) $wait);
        }
    }

    /** Seconds to wait before trying again, or null to hand the response back. */
    private function waitFor(ResponseInterface $response, int $tries): ?int
    {
        $status = $response->getStatusCode();
        if (($status !== 429 && $status !== 503) || $tries >= $this->maxAttempts) {
            return null;
        }
        $wait = self::parseRetryAfter($response->getHeaderLine('Retry-After'), $this->now());
        return $wait === null || $wait > $this->retryAfterCap ? null : $wait;
    }
}
