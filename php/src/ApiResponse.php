<?php

declare(strict_types=1);

namespace Lingara;

/**
 * A JSON operation's result: the decoded `200` body and the Lingara-Version
 * echo. Not called `Response`, so it never collides with a framework's in a
 * caller's `use` list.
 *
 * @template T of object
 */
final readonly class ApiResponse
{
    /**
     * @param T $value
     */
    public function __construct(
        public object $value,
        public ?string $servedVersion,
    ) {}
}
