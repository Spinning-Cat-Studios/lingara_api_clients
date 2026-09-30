<?php

declare(strict_types=1);

namespace Lingara\Internal;

use Lingara\Exception\ApiException;
use Lingara\Exception\LingaraException;
use Lingara\Exception\MaintenanceException;
use Lingara\Exception\OAuthException;
use Lingara\Exception\TransportKind;
use Psr\Http\Message\ResponseInterface;

/**
 * Response → K3 exception in C2 D4's precedence, a failed send → its
 * TransportKind in D4's order, and any cause → a scrubbed stand-in.
 *
 * @internal
 */
final class ErrorMapper
{
    private const BODY_BYTES = 1024;

    /** curl's TLS error numbers: SSL connect, bad certificate, cipher, CA, peer verification. */
    private const TLS_ERRNO = [35, 51, 53, 54, 58, 59, 60, 64, 66, 77, 80, 82, 83, 90, 91];
    private const TLS_TEXT = '/\bSSL\b|\bTLS\b|OpenSSL|crypto|certificate|handshake/i';

    /**
     * What the stacks report for DNS failure, an unreachable host and a
     * connect timeout, which stay Connect however long they took. curl's
     * transfer timeout reads "Operation timed out after …" and is not among
     * them; the bare OS text is macOS's connect timeout on StreamHandler.
     * "Connection refused" is not either: on PHP 8.5 StreamHandler reports an
     * expired header wait that way, and a real refusal is instant, so it is
     * Connect only before the bound (D4 step 4).
     */
    private const CONNECT_TEXT = "/Could not resolve|Couldn't resolve|getaddrinfo|Couldn't connect|Failed to connect"
        . '|No route to host|Network is unreachable|Connection timed out|Resolving timed out|Operation timed out(?! after)/i';

    /**
     * A non-2xx response as its K3 exception. $endpoint is `v1` or `token`.
     * The response is a sensitive parameter because the exception's trace
     * starts here, and a client's response object may reach its request.
     */
    public static function refusal(
        string $endpoint,
        #[\SensitiveParameter]
        ResponseInterface $response,
        string $body,
        \DateTimeImmutable $now,
    ): LingaraException {
        $status = $response->getStatusCode();
        $retryAfter = Retry::parseRetryAfter($response->getHeaderLine('Retry-After'), $now);
        if ($status === 503 && !self::isJson($response)) {
            return new MaintenanceException(self::truncate($body, self::BODY_BYTES), $retryAfter);
        }
        $fields = self::object($body);
        if ($endpoint === 'token') {
            return self::oauth($status, $fields, $retryAfter);
        }
        $code = $fields['code'] ?? null;
        $message = $fields['error'] ?? null;
        if (!is_string($code) || !is_string($message)) {
            [$code, $message] = ["http_{$status}", "HTTP {$status}"];
        }
        $served = $response->getHeaderLine('Lingara-Version');
        return new ApiException($status, $code, $message, $retryAfter, null, $served === '' ? null : $served);
    }

    /** @param array<mixed> $fields RFC 6749 §5.2's, or `http_<status>` for any other body */
    private static function oauth(int $status, array $fields, ?int $retryAfter): OAuthException
    {
        $error = $fields['error'] ?? null;
        if (!is_string($error)) {
            return new OAuthException($status, "http_{$status}", null, $retryAfter);
        }
        $description = $fields['error_description'] ?? null;
        return new OAuthException($status, $error, is_string($description) ? $description : null, $retryAfter);
    }

    /**
     * A failure before the status line, in D4's order: TLS; a connect failure
     * the stack reports (curl's errno through Guzzle's handler context, else
     * the message); Timeout once the bound has passed; then Connect.
     */
    public static function sendKind(\Throwable $e, float $elapsed, float $bound): TransportKind
    {
        $text = self::messages($e);
        $errno = self::curlErrno($e);
        if (in_array($errno, self::TLS_ERRNO, true) || preg_match(self::TLS_TEXT, $text) === 1) {
            return TransportKind::Tls;
        }
        $connect = $errno !== null ? self::curlConnect($e, $errno) : preg_match(self::CONNECT_TEXT, $text) === 1;
        return $connect || $elapsed < $bound ? TransportKind::Connect : TransportKind::Timeout;
    }

    /**
     * A stand-in for $e that keeps its class and message, with every secret
     * replaced by [REDACTED], and no previous of its own: the original, and
     * the request it may carry, are dropped.
     *
     * The stand-in is created here, so its own trace holds this frame's
     * arguments: $e is sensitive too.
     *
     * @param list<string> $secrets
     */
    public static function scrub(#[\SensitiveParameter] \Throwable $e, #[\SensitiveParameter] array $secrets): \RuntimeException
    {
        $text = $e::class . ': ' . $e->getMessage();
        foreach ($secrets as $secret) {
            if ($secret !== '') {
                $text = str_replace($secret, '[REDACTED]', $text);
            }
        }
        return new \RuntimeException($text);
    }

    public static function mediaType(ResponseInterface $response): string
    {
        return strtolower(trim(explode(';', $response->getHeaderLine('Content-Type'))[0]));
    }

    public static function isJson(ResponseInterface $response): bool
    {
        $media = self::mediaType($response);
        return $media === 'application/json' || str_ends_with($media, '+json');
    }

    /** At most $max bytes, cut on a UTF-8 character boundary. */
    public static function truncate(string $text, int $max): string
    {
        if (strlen($text) <= $max) {
            return $text;
        }
        $cut = substr($text, 0, $max);
        return preg_match('//u', $cut) === 1 ? $cut : self::dropPartial($cut);
    }

    /** @return array<mixed> a JSON object body's fields, or [] for anything else */
    public static function object(string $body): array
    {
        try {
            $value = json_decode($body, true, 512, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return [];
        }
        return is_array($value) && !array_is_list($value) ? $value : [];
    }

    /** A cut that split a character loses its one to three trailing bytes. */
    private static function dropPartial(string $cut): string
    {
        for ($drop = 1; $drop <= 3; $drop++) {
            $candidate = substr($cut, 0, -$drop);
            if (preg_match('//u', $candidate) === 1) {
                return $candidate;
            }
        }
        return $cut;
    }

    private static function messages(\Throwable $e): string
    {
        $text = [];
        for ($link = $e; $link !== null; $link = $link->getPrevious()) {
            $text[] = $link->getMessage();
        }
        return implode(' | ', $text);
    }

    /** curl's error number, from Guzzle's handler context when there is one. */
    private static function curlErrno(\Throwable $e): ?int
    {
        $errno = self::handlerContext($e)['errno'] ?? null;
        return is_int($errno) && $errno > 0 ? $errno : null;
    }

    /** Resolve and connect failures, and a timeout while nothing had connected. */
    private static function curlConnect(\Throwable $e, int $errno): bool
    {
        if (in_array($errno, [5, 6, 7], true)) {
            return true;
        }
        $connected = self::handlerContext($e)['connect_time'] ?? 0;
        return $errno === 28 && is_numeric($connected) && (float) $connected === 0.0;
    }

    /**
     * Guzzle's handler context (curl's errno and timings), read by duck typing
     * so Guzzle is never a dependency.
     *
     * @return array<mixed>
     */
    private static function handlerContext(\Throwable $e): array
    {
        $context = method_exists($e, 'getHandlerContext') ? $e->getHandlerContext() : null;
        return is_array($context) ? $context : [];
    }
}
