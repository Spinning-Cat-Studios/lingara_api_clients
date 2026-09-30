<?php

declare(strict_types=1);

namespace Lingara\Internal;

/**
 * One dispatched server-sent event: its name (`message` when none was sent)
 * and its data lines joined by \n.
 *
 * @internal
 */
final readonly class Frame
{
    public function __construct(
        public string $event,
        public string $data,
    ) {}
}
