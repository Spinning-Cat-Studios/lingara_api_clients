<?php

declare(strict_types=1);

namespace Lingara\Internal;

/**
 * One dispatched server-sent event: its name (`message` when none was sent),
 * its data lines joined by \n, and the last-event-id buffer as it stood at
 * dispatch (null while no `id` field has set it). Only K5a's tail reads the
 * id (ADR 30.9.26aa D7).
 *
 * @internal
 */
final readonly class Frame
{
    public function __construct(
        public string $event,
        public string $data,
        public ?string $id = null,
    ) {}
}
