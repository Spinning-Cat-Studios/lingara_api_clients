<?php

declare(strict_types=1);

namespace Lingara\Tests\Support;

use Psr\Clock\ClockInterface;

/** A virtual clock that moves only when a test says so, and a recording sleeper beside it. */
final class FakeClock implements ClockInterface
{
    /** @var list<float> */
    public array $sleeps = [];

    public function __construct(public float $now = 1_790_000_000.0) {}

    public function now(): \DateTimeImmutable
    {
        return new \DateTimeImmutable('@' . sprintf('%.6F', $this->now));
    }

    public function advance(float $seconds): void
    {
        $this->now += $seconds;
    }

    /** The sleeper seam: records the wait and returns at once. */
    public function sleep(float $seconds): void
    {
        $this->sleeps[] = $seconds;
    }
}
