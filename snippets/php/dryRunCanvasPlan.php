<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin dryRunCanvasPlan
use Lingara\Model\CanvasPlan;
// lingara:end

function dryRunCanvasPlan(Client $client): void
{
    // lingara:begin dryRunCanvasPlan
    $plan = new CanvasPlan(['ops' => [
        ['handle' => 'c1', 'kind' => 'client', 'op' => 'create', 'body' => ['name' => 'Lesson server', 'scopes' => ['events:read']]],
    ]]);
    // A refused op is an answer, not an error: the call returns with ok false.
    $run = $client->canvases()->dryRunCanvasPlan($plan)->value;
    foreach ($run->getVerdicts() as $verdict) {
        // Branch on code; show the English error only as a fallback.
        echo $verdict->getHandle(), ': ', $verdict->getVerdict()->value, ' ', $verdict->getCode() ?? '', "\n";
    }
    echo $run->getOk() ? "ready to apply\n" : "fix the refused ops first\n";
    // lingara:end
}
