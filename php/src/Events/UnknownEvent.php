<?php

declare(strict_types=1);

namespace Lingara\Events;

/**
 * An event whose type this library does not know (ADR 30.9.26aa D3): the
 * catalogue is additive, so a type newer than the library arrives here
 * rather than as an error. Acknowledge it like any other event, and log it:
 * a receiver that drops it silently loses it.
 */
final readonly class UnknownEvent implements Event
{
    /**
     * @param mixed $data the `data` value as decoded JSON, objects as \stdClass
     */
    public function __construct(
        public string $id,
        public string $type,
        public string $createdAt,
        public string $apiVersion,
        public string $subject,
        public mixed $data = null,
    ) {}
}
