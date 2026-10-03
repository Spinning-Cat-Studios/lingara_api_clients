<?php

declare(strict_types=1);

namespace Lingara\Events;

/**
 * A webhook delivery that did not verify (CONTRACT.md appendix W; ADR
 * 30.9.26aa D4). Not a LingaraException, on purpose: a `catch
 * (LingaraException $e)` around API calls must not also swallow a forged
 * webhook, and no Lingara server answered anything. Answer the delivery
 * with a 4xx.
 *
 * reason() is one of the class constants. The message never holds a secret,
 * a signature or the body.
 */
final class VerificationException extends \RuntimeException
{
    public const MISSING_HEADER = 'missing_header';
    public const MALFORMED_HEADER = 'malformed_header';
    public const TIMESTAMP_TOO_OLD = 'timestamp_too_old';
    public const TIMESTAMP_TOO_NEW = 'timestamp_too_new';
    public const NO_MATCHING_SIGNATURE = 'no_matching_signature';
    public const MALFORMED_PAYLOAD = 'malformed_payload';

    /** @internal thrown by Webhook */
    public function __construct(
        private readonly string $reason,
        string $detail,
    ) {
        parent::__construct("webhook verification failed: {$reason}: {$detail}");
    }

    public function reason(): string
    {
        return $this->reason;
    }
}
