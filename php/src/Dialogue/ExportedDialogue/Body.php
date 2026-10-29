<?php

declare(strict_types=1);

namespace Lingara\Dialogue\ExportedDialogue;

use Lingara\Dialogue\ExportedDialogue;

/**
 * A 200 export. `irJson` is the body exactly as sent, never parsed: its
 * bytes are canonical, and `etag` is their SHA-256, so store them as
 * received. Decoding them is yours.
 */
final readonly class Body implements ExportedDialogue
{
    public function __construct(
        public int $revision,
        public string $etag,
        public string $irJson,
        public ?string $servedVersion,
    ) {}
}
