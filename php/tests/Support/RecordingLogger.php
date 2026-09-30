<?php

declare(strict_types=1);

namespace Lingara\Tests\Support;

use Psr\Log\AbstractLogger;

/** A PSR-3 logger that keeps every line, by level. */
final class RecordingLogger extends AbstractLogger
{
    /** @var array<string, list<string>> */
    public array $lines = [];

    /**
     * Untyped $message, as in ErrorLogLogger, so the lowest-versions leg's
     * psr/log 1 accepts this override.
     *
     * @param string|\Stringable $message
     * @param array<mixed>       $context
     */
    public function log($level, $message, array $context = []): void
    {
        $this->lines[is_string($level) ? $level : 'other'][] = (string) $message;
    }
}
