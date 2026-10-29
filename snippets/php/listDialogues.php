<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function listDialogues(Client $client): void
{
    // lingara:begin listDialogues
    // One page; pass its next_cursor back as `cursor` for the next, or let
    // pageDialogues() walk them all.
    $page = $client->dialogues()->listDialogues(['name' => 'market', 'limit' => 20])->value;
    foreach ($page->getItems() as $dialogue) {
        echo $dialogue->getId(), ' ', $dialogue->getName(), ' (', $dialogue->getIrId(), '): ',
            $dialogue->getNpcCount(), ' NPCs, ', $dialogue->getCheckpointCount(), " checkpoints\n";
    }
    echo $page->getHasMore() ? "more after {$page->getNextCursor()}\n" : "that is all\n";
    // lingara:end
}
