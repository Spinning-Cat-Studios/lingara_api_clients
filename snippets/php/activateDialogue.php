<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin activateDialogue
use Lingara\Model\ActivateDialogueRequest;
// lingara:end

function activateDialogue(Client $client, string $dialogueId): void
{
    // lingara:begin activateDialogue
    // Promote the draft you read: a stale or missing draft is 409
    // dialogue_conflict and writes nothing.
    $dialogues = $client->dialogues();
    $draft = $dialogues->getDialogue($dialogueId)->value->getDraft();
    if ($draft !== null) {
        $activated = $dialogues->activateDialogue($dialogueId, new ActivateDialogueRequest(['draft_version' => $draft->getDraftVersion()]))->value;
        echo 'active revision ', $activated->getActive()?->getRevision(), "\n";
    }
    // lingara:end
}
