<?php

declare(strict_types=1);

namespace Lingara\Exception;

/**
 * A /v1 refusal, or a stream's `error` event (then status() is 200).
 *
 * getCode() is the HTTP status, the same as status(); the server's string
 * code is errorCode(), so no two methods named "code" disagree.
 */
final class ApiException extends \RuntimeException implements LingaraException
{
    public function __construct(
        private readonly int $status,
        private readonly string $errorCode,
        string $message,
        private readonly ?int $retryAfter = null,
        private readonly ?string $planId = null,
        private readonly ?string $servedVersion = null,
    ) {
        parent::__construct($message, $status);
    }

    public function status(): int
    {
        return $this->status;
    }

    /** The server's code, such as `rate_limited`, or `http_<status>` for a non-envelope body. */
    public function errorCode(): string
    {
        return $this->errorCode;
    }

    /** Retry-After in whole seconds, or null when the response carried none. */
    public function retryAfter(): ?int
    {
        return $this->retryAfter;
    }

    /** The plan id an `error` event carried, or null. */
    public function planId(): ?string
    {
        return $this->planId;
    }

    /** The Lingara-Version echo, or null when the server sent none. */
    public function servedVersion(): ?string
    {
        return $this->servedVersion;
    }
}
