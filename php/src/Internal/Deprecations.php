<?php

declare(strict_types=1);

namespace Lingara\Internal;

use Lingara\DeprecationNotice;
use Lingara\Version;
use Psr\Http\Message\ResponseInterface;
use Psr\Log\LoggerInterface;

/**
 * Per client (K2): reads the served version off each response, reports a
 * deprecation once per response, to the hook or, with no hook, as one warning
 * per version id, and separately warns once per served id that is not the
 * version the models were generated from (Backend ADR 30.9.26a §4).
 *
 * @internal
 */
final class Deprecations
{
    /** @var (\Closure(DeprecationNotice): void)|null */
    private readonly ?\Closure $hook;

    /** @var array<string, true> */
    private array $warned = [];

    /**
     * Its own set: sharing $warned would let a version that is both deprecated
     * and mismatched warn only once in total.
     *
     * @var array<string, true>
     */
    private array $mismatched = [];

    /** @param (callable(DeprecationNotice): void)|null $hook */
    public function __construct(
        ?callable $hook,
        private readonly LoggerInterface $logger,
        private readonly string $generatedFor = Version::GENERATED_FOR_VERSION,
    ) {
        $this->hook = $hook === null ? null : \Closure::fromCallable($hook);
    }

    /** The Lingara-Version echo, or null, after reporting any deprecation or mismatch. */
    public function observe(ResponseInterface $response, string $requestUrl): ?string
    {
        $notice = self::notice($response, $requestUrl);
        if ($notice !== null) {
            $this->report($notice);
        }
        $served = $response->getHeaderLine('Lingara-Version');
        if ($served === '') {
            return null;
        }
        if ($served !== $this->generatedFor && $this->first($this->mismatched, $served)) {
            $this->logger->warning("Lingara API version {$served} served this response, but this library's models "
                . "were generated for {$this->generatedFor}; response shapes may differ. Pin the OAuth client to "
                . "{$this->generatedFor} or upgrade the library.");
        }
        return $served;
    }

    /** The notice for a response, or null when it carries no Deprecation. */
    public static function notice(ResponseInterface $response, string $requestUrl): ?DeprecationNotice
    {
        $raw = trim($response->getHeaderLine('Deprecation'));
        if ($raw === '') {
            return null;
        }
        $sunset = $response->hasHeader('Sunset') ? $response->getHeaderLine('Sunset') : null;
        $link = $response->hasHeader('Link') ? $response->getHeaderLine('Link') : null;
        $version = $response->getHeaderLine('Lingara-Version');
        return new DeprecationNotice(
            $version === '' ? null : $version,
            self::parseDeprecation($raw),
            $sunset === null ? null : self::parseSunset($sunset),
            $link,
            $link === null ? null : self::linkTarget($link, $requestUrl),
            array_filter(['Deprecation' => $raw, 'Sunset' => $sunset, 'Link' => $link], static fn(?string $v): bool => $v !== null),
        );
    }

    public static function parseDeprecation(string $value): ?\DateTimeImmutable
    {
        if (preg_match('/\A@(-?\d{1,15})\z/', trim($value), $m) !== 1) {
            return null;
        }
        return new \DateTimeImmutable("@{$m[1]}");
    }

    /** An IMF-fixdate only: the obsolete HTTP-date formats are not a Sunset. */
    public static function parseSunset(string $value): ?\DateTimeImmutable
    {
        return Retry::httpDate($value);
    }

    /** The Link's first target, resolved against the request URL (RFC 3986 §5.2). */
    public static function linkTarget(string $link, string $base): ?string
    {
        if (preg_match('/\A\s*<([^>]*)>/', $link, $m) !== 1) {
            return null;
        }
        return UriResolver::resolve($base, $m[1]);
    }

    private function report(DeprecationNotice $notice): void
    {
        if ($this->hook === null) {
            // An absent echo counts as one id.
            if ($this->first($this->warned, (string) $notice->version)) {
                $sunset = isset($notice->headers['Sunset']) ? "; sunset {$notice->headers['Sunset']}" : '';
                $name = $notice->version ?? '(unnamed)';
                $this->logger->warning("Lingara API version {$name} is deprecated{$sunset}. See GET /v1/versions.");
            }
            return;
        }
        try {
            ($this->hook)($notice);
        } catch (\Throwable $e) {
            $this->logger->debug('the Lingara deprecation hook threw ' . $e::class . '; the call continues');
        }
    }

    /** @param array<string, true> $seen */
    private function first(array &$seen, string $id): bool
    {
        if (isset($seen[$id])) {
            return false;
        }
        $seen[$id] = true;
        return true;
    }
}
