<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin renameDialogue
use Lingara\Model\RenameDialogueRequest;
// lingara:end

function renameDialogue(Client $client, string $dialogueId): void
{
    // lingara:begin renameDialogue
    $dialogue = $client->dialogues()->renameDialogue($dialogueId, new RenameDialogueRequest(['name' => 'Night market keepers']))->value;
    echo 'renamed to ', $dialogue->getName(), "\n";
    // lingara:end
}
