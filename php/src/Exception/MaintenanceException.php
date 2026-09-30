<?php

declare(strict_types=1);

namespace Lingara\Exception;

/** A 503 whose Content-Type is not JSON, from either endpoint: the API is under maintenance. */
final class MaintenanceException extends \RuntimeException implements LingaraException
{
    public function __construct(
        private readonly string $body,
        private readonly ?int $retryAfter = null,
    ) {
        parent::__construct('the Lingara API is under maintenance', 503);
    }

    /** The response text, at most 1 KiB, cut on a UTF-8 character boundary. */
    public function body(): string
    {
        return $this->body;
    }

    /** Retry-After in whole seconds, or null when the response carried none. */
    public function retryAfter(): ?int
    {
        return $this->retryAfter;
    }
}
