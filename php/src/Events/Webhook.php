<?php

declare(strict_types=1);

namespace Lingara\Events;

use Lingara\Events\Generated\EventParser;
use Lingara\Internal\Json;
use Lingara\Internal\Secrets;
use Lingara\Internal\SystemClock;
use Psr\Clock\ClockInterface;
use Psr\Http\Message\MessageInterface;

/**
 * Verifies a Lingara webhook delivery (CONTRACT.md appendix W; ADR 30.9.26aa
 * D4): the Standard Webhooks scheme, keyed on a `lgr_whsec_` secret, with no
 * dependency on a Standard Webhooks package.
 *
 *     $webhook = new Webhook(getenv('LINGARA_WEBHOOK_SECRET'));
 *     $event = $webhook->verify(file_get_contents('php://input'), getallheaders());
 *
 * Pass the body exactly as received: a parsed and re-encoded body no longer
 * matches its signature. During a rotation pass both live secrets. `clock`
 * is a testing seam, read for the 300 s tolerance, which is not
 * configurable. The secrets live in Secrets, so no rendering of a Webhook
 * shows them.
 */
final class Webhook
{
    private const PREFIX = 'lgr_whsec_';
    private const MIN_KEY_BYTES = 24;
    private const TOLERANCE = 300;
    private const HEADERS = ['webhook-id', 'webhook-timestamp', 'webhook-signature'];

    private readonly object $handle;
    private readonly ClockInterface $clock;

    /**
     * @param string|list<string> $secrets one secret, or each live one during a rotation
     *
     * @throws \InvalidArgumentException for a secret that is not `lgr_whsec_`
     *                                   and padded base64 of at least 24 bytes
     */
    public function __construct(
        #[\SensitiveParameter]
        string|array $secrets,
        ?ClockInterface $clock = null,
    ) {
        $keys = [];
        foreach (is_string($secrets) ? [$secrets] : $secrets as $secret) {
            $keys[] = self::key($secret);
        }
        if ($keys === []) {
            throw new \InvalidArgumentException('a Webhook needs at least one secret');
        }
        $this->handle = Secrets::handle();
        Secrets::put($this->handle, 'keys', $keys);
        $this->clock = $clock ?? new SystemClock();
    }

    /**
     * Verifies the signature, then parses the body into its Event: an
     * UnknownEvent for a type this library does not know.
     *
     * @param array<string, string|list<string>>|MessageInterface $headers the request's
     *        headers, looked up case-insensitively, or any PSR-7 request
     *
     * @throws VerificationException
     */
    public function verify(string $body, array|MessageInterface $headers): Event
    {
        $this->verifySignature($body, $headers);
        try {
            $value = Json::decode($body);
            $event = EventParser::fromValue($value);
        } catch (\JsonException) {
            throw new VerificationException(VerificationException::MALFORMED_PAYLOAD, 'the body is not JSON');
        } catch (\UnexpectedValueException $e) {
            throw new VerificationException(VerificationException::MALFORMED_PAYLOAD, $e->getMessage());
        }
        // fromValue() has checked that the envelope is an object with a string id.
        if (!$value instanceof \stdClass || $value->id !== self::header($headers, 'webhook-id')) {
            throw new VerificationException(VerificationException::MALFORMED_PAYLOAD, "the event's id is not the webhook-id header");
        }
        return $event;
    }

    /**
     * Verifies the signature alone, for a signed body that is not an event
     * envelope (an app-kit request). Returns nothing on success.
     *
     * @param array<string, string|list<string>>|MessageInterface $headers as verify()
     *
     * @throws VerificationException with any reason but malformed_payload
     */
    public function verifySignature(string $body, array|MessageInterface $headers): void
    {
        [$id, $timestamp, $signatures] = array_map(static fn(string $name): string => self::header($headers, $name), self::HEADERS);
        $this->checkTimestamp($timestamp);
        $keys = Secrets::get($this->handle, 'keys');
        $content = "{$id}.{$timestamp}.{$body}";
        foreach (is_array($keys) ? $keys : [] as $key) {
            $expected = hash_hmac('sha256', $content, is_string($key) ? $key : '', true);
            foreach (self::signatures($signatures) as $signature) {
                if (hash_equals($expected, $signature)) {
                    return;
                }
            }
        }
        throw new VerificationException(VerificationException::NO_MATCHING_SIGNATURE, 'no v1 signature matches a secret');
    }

    /** @return array<string, mixed> */
    public function __debugInfo(): array
    {
        return ['secrets' => '[REDACTED]'];
    }

    /** @return array<string, mixed> */
    public function __serialize(): array
    {
        throw new \LogicException('a Webhook cannot be serialized');
    }

    /** @param array<mixed> $data */
    public function __unserialize(array $data): void
    {
        throw new \LogicException('a Webhook cannot be unserialized');
    }

    /** The HMAC key: the strict base64 after the prefix, matched before it is decoded. */
    private static function key(#[\SensitiveParameter] mixed $secret): string
    {
        $encoded = is_string($secret) && str_starts_with($secret, self::PREFIX) ? substr($secret, strlen(self::PREFIX)) : '';
        if (preg_match('#^[A-Za-z0-9+/]+={0,2}$#', $encoded) !== 1 || strlen($encoded) % 4 !== 0) {
            throw new \InvalidArgumentException('a webhook secret is lgr_whsec_ followed by padded base64');
        }
        $key = base64_decode($encoded, true);
        if ($key === false || strlen($key) < self::MIN_KEY_BYTES) {
            throw new \InvalidArgumentException('a webhook secret decodes to at least ' . self::MIN_KEY_BYTES . ' bytes');
        }
        return $key;
    }

    /** @param array<string, string|list<string>>|MessageInterface $headers */
    private static function header(array|MessageInterface $headers, string $name): string
    {
        if ($headers instanceof MessageInterface) {
            $found = $headers->hasHeader($name) ? $headers->getHeaderLine($name) : null;
        } else {
            $found = null;
            foreach ($headers as $key => $value) {
                if (strcasecmp((string) $key, $name) === 0) {
                    $found = is_array($value) ? implode(', ', $value) : $value;
                }
            }
        }
        return $found ?? throw new VerificationException(VerificationException::MISSING_HEADER, "no {$name} header");
    }

    private function checkTimestamp(string $timestamp): void
    {
        if (!ctype_digit($timestamp)) {
            throw new VerificationException(VerificationException::MALFORMED_HEADER, 'webhook-timestamp is not a whole number of seconds');
        }
        // A digit string past PHP_INT_MAX saturates there: far in the future.
        $age = $this->clock->now()->getTimestamp() - (int) $timestamp;
        if ($age > self::TOLERANCE) {
            throw new VerificationException(VerificationException::TIMESTAMP_TOO_OLD, 'webhook-timestamp is more than 300 s old');
        }
        if ($age < -self::TOLERANCE) {
            throw new VerificationException(VerificationException::TIMESTAMP_TOO_NEW, 'webhook-timestamp is more than 300 s ahead');
        }
    }

    /**
     * Each decodable `v1` signature: an element with another version prefix,
     * or one that is not base64, is skipped.
     *
     * @return list<string>
     */
    private static function signatures(string $header): array
    {
        $out = [];
        foreach (explode(' ', $header) as $element) {
            $decoded = str_starts_with($element, 'v1,') ? base64_decode(substr($element, 3), true) : false;
            if ($decoded !== false && $decoded !== '') {
                $out[] = $decoded;
            }
        }
        return $out;
    }
}
