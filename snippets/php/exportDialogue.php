<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin exportDialogue
use Lingara\Dialogue\ExportedDialogue\Body;
// lingara:end

/** @param array{etag: string, irJson: string}|null $cached */
function exportDialogue(Client $client, string $dialogueId, ?array $cached = null): void
{
    // lingara:begin exportDialogue
    // Cache by ETag: the body is canonical bytes, so store it exactly as
    // received. A 304 still spends one unit of the rate budget.
    $exported = $client->dialogues()->exportDialogue($dialogueId, ifNoneMatch: $cached['etag'] ?? null);
    if ($exported instanceof Body) {
        $cached = ['etag' => $exported->etag, 'irJson' => $exported->irJson];
        echo 'revision ', $exported->revision, " is new\n";
    } else {
        echo 'revision ', $exported->revision, " is unchanged\n";
    }
    // lingara:end
}
