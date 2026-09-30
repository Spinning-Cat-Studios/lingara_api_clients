<?php

declare(strict_types=1);

namespace Lingara\Exception;

/**
 * A call with no usable HTTP answer: kind() says why.
 *
 * No HTTP client's exception is ever kept as the cause, because a PSR-18
 * exception carries the request, Authorization header included. getPrevious()
 * is instead a plain \RuntimeException whose message is the original's class
 * and message with every credential replaced by [REDACTED], and which has no
 * previous of its own.
 */
final class TransportException extends \RuntimeException implements LingaraException
{
    public function __construct(
        private readonly TransportKind $kind,
        string $message,
        ?\RuntimeException $scrubbedCause = null,
    ) {
        parent::__construct("transport failure: {$kind->value}: {$message}", 0, $scrubbedCause);
    }

    public function kind(): TransportKind
    {
        return $this->kind;
    }
}
