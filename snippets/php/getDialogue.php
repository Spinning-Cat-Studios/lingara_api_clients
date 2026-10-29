<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function getDialogue(Client $client, string $dialogueId): void
{
    // lingara:begin getDialogue
    // The draft and the active revision, inline; either may be null.
    $dialogue = $client->dialogues()->getDialogue($dialogueId)->value;
    $draft = $dialogue->getDraft();
    $active = $dialogue->getActive();
    echo $dialogue->getName(), ': draft ', $draft?->getDraftVersion() ?? 'none',
        ', active revision ', $active?->getRevision() ?? 'none', ' since ', $active?->getActivatedAt() ?? 'never', "\n";
    // lingara:end
}
