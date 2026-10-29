<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function pageDialogues(Client $client): void
{
    // lingara:begin pageDialogues
    // Every dialogue the filters match: the helper follows next_cursor and
    // resends the filters on every page. It never sleeps or polls.
    foreach ($client->dialogues()->pageDialogues(['name' => 'market']) as $dialogue) {
        echo $dialogue->getId(), ' ', $dialogue->getName(), ' ', $dialogue->getActiveRevision() ?? 'never activated', "\n";
    }
    // lingara:end
}
