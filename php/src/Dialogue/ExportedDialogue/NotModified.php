<?php

declare(strict_types=1);

namespace Lingara\Dialogue\ExportedDialogue;

use Lingara\Dialogue\ExportedDialogue;

/**
 * A 304 export: the ETag you sent as ifNoneMatch still names this revision,
 * so the copy you hold is current. It still spent one unit of the rate
 * budget.
 */
final readonly class NotModified implements ExportedDialogue
{
    public function __construct(
        public int $revision,
        public string $etag,
        public ?string $servedVersion,
    ) {}
}
