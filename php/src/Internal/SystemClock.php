<?php

declare(strict_types=1);

namespace Lingara\Internal;

use Psr\Clock\ClockInterface;

/**
 * The default PSR-20 clock: psr/clock ships none.
 *
 * @internal
 */
final class SystemClock implements ClockInterface
{
    public function now(): \DateTimeImmutable
    {
        return new \DateTimeImmutable('now', new \DateTimeZone('UTC'));
    }
}
