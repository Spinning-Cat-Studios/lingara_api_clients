<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin planDialogue
use Lingara\Model\DialoguePlanRequest;
use Lingara\Model\DialoguePlanResponseOneOf;
use Lingara\Model\PlanReason;
// lingara:end

function planDialogue(Client $client, object $document): void
{
    // lingara:begin planDialogue
    // The first plan of a Procedural dialogue. Inside the embed kits the kit
    // calls this for you. Never retried: each call spends one embed:play
    // unit, a keep too. $document is your dialogue.v2 one, sent as given.
    $request = new DialoguePlanRequest([
        'document' => $document,
        'npc' => 'ma_ren',
        'reason' => PlanReason::START,
        'id_prefix' => 'p1',
        'turns' => 0,
        'beats' => 0,
    ]);
    $answer = $client->planDialogue($request)->value;
    if ($answer instanceof DialoguePlanResponseOneOf) {
        foreach ($answer->getFragment()->getBeats() as $beat) {
            echo $beat->getId(), ' ', $beat->getAtom()->value, "\n";
        }
    } else {
        // A keep is a result: your frontier stands (on a first plan, walk the seed).
        echo 'kept: ', $answer->getReason()->value, "\n";
    }
    // lingara:end
}
