<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin sendDialogueTurn
use Lingara\Model\DialogueEntry;
use Lingara\Model\DialogueTurnRequest;
use Lingara\Model\Npc;
use Lingara\Model\Speaker;
use Lingara\Stream\SendDialogueTurnEvent;
// lingara:end

function sendDialogueTurn(Client $client): void
{
    // lingara:begin sendDialogueTurn
    $history = [new DialogueEntry(['speaker' => Speaker::NPC, 'text' => '来来来，刚出锅的饺子！'])];
    $turn = new DialogueTurnRequest([
        'npc' => new Npc(['name' => 'Auntie Lin', 'persona' => 'a street-food vendor who likes to haggle']),
        'source_lang' => 'en',
        'target_lang' => 'zh',
        'level' => 3,
        'line' => '饺子多少钱？',
        'history' => $history,
    ]);
    $reply = '';
    foreach ($client->sendDialogueTurn($turn) as $event) {
        if ($event instanceof SendDialogueTurnEvent\Delta) {
            $reply .= $event->data->getText();
        }
    }
    echo $reply, "\n";
    // The next turn carries both lines: at most 12 entries, the reply cut to
    // its first 500 characters.
    $history[] = new DialogueEntry(['speaker' => Speaker::PLAYER, 'text' => $turn->getLine()]);
    $history[] = new DialogueEntry(['speaker' => Speaker::NPC, 'text' => mb_substr($reply, 0, 500)]);
    $history = array_slice($history, -12);
    // lingara:end
}
