<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin createDialogue
use Lingara\Model\CreateDialogueRequest;
// lingara:end

function createDialogue(Client $client): void
{
    // lingara:begin createDialogue
    // The definition is a dialogue.v1 document, kept as plain JSON objects;
    // the server validates it on every save. Warnings are advice, never a
    // refusal.
    $definition = json_decode(<<<'JSON'
        {"schema": "dialogue.v1", "id": "night_market_keepers", "start": "greet",
         "npcs": [{"id": "old_wei", "name": "Old Wei"}],
         "checkpoints": [{"id": "greet", "goal": "Greet the stranger", "may_disclose": [],
                          "edges": [{"id": "thanked", "to": "success", "when": "the player says thank you"}]}],
         "facts": []}
        JSON, false, 512, JSON_THROW_ON_ERROR);
    $created = $client->dialogues()->createDialogue(new CreateDialogueRequest([
        'name' => 'Night market',
        'definition' => $definition,
    ]))->value;
    echo 'created ', $created->getId(), ' at draft version ', $created->getDraft()?->getDraftVersion(), "\n";
    foreach ($created->getWarnings() as $warning) {
        echo 'warning ', $warning->getCode()->value, ' at ', $warning->getPath(), "\n";
    }
    // lingara:end
}
