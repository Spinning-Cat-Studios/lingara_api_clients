<?php

declare(strict_types=1);

namespace Lingara\Exception;

/** A token-endpoint refusal: RFC 6749 §5.2, or `http_<status>` for any other body. */
final class OAuthException extends \RuntimeException implements LingaraException
{
    public function __construct(
        private readonly int $status,
        private readonly string $error,
        private readonly ?string $description = null,
        private readonly ?int $retryAfter = null,
    ) {
        $text = $description === null ? $error : "{$error}: {$description}";
        parent::__construct("token endpoint: {$text} (HTTP {$status})", $status);
    }

    public function status(): int
    {
        return $this->status;
    }

    public function error(): string
    {
        return $this->error;
    }

    /** The response's `error_description`, or null. */
    public function description(): ?string
    {
        return $this->description;
    }

    /** Retry-After in whole seconds, or null when the response carried none. */
    public function retryAfter(): ?int
    {
        return $this->retryAfter;
    }
}
