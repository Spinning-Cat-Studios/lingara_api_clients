<?php

declare(strict_types=1);

namespace Lingara\Internal;

use Psr\Log\LoggerInterface;
use Psr\Log\LoggerTrait;
use Psr\Log\LogLevel;

/**
 * The default PSR-3 logger: every level but debug goes to error_log(), PHP's
 * own logging facility, which writes to the `error_log` ini destination or
 * the SAPI's log whatever `error_reporting` says, and which no error handler
 * sees. So an application whose handler throws on every error still gets
 * the one deprecation warning C2 requires, and never a failed call from it.
 * Any PSR-3 logger (Monolog, Laravel's, Symfony's) replaces it.
 *
 * @internal
 */
final class ErrorLogLogger implements LoggerInterface
{
    use LoggerTrait;

    /**
     * Untyped parameters, so this one class satisfies psr/log 1, 2 and 3.
     *
     * @param mixed                $level
     * @param string|\Stringable   $message
     * @param array<string, mixed> $context
     */
    public function log($level, $message, array $context = []): void
    {
        if ($level !== LogLevel::DEBUG) {
            error_log("lingara: {$message}");
        }
    }
}
