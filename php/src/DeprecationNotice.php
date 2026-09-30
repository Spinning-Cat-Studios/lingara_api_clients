<?php

declare(strict_types=1);

namespace Lingara;

/**
 * What a response under a deprecated version says about it (K2), handed to
 * the client's `onDeprecation` hook. An unparseable header leaves its parsed
 * field null, never an error.
 */
final readonly class DeprecationNotice
{
    /**
     * @param ?string               $linkRaw    the raw Link header
     * @param ?string               $linkTarget its target resolved against the request URL (RFC 8288 §3.2)
     * @param array<string, string> $headers    the raw Deprecation, Sunset and Link values present
     */
    public function __construct(
        public ?string $version,
        public ?\DateTimeImmutable $deprecatedAt,
        public ?\DateTimeImmutable $sunsetAt,
        public ?string $linkRaw,
        public ?string $linkTarget = null,
        public array $headers = [],
    ) {}
}
