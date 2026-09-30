<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin sendTutorMessage
use Lingara\Model\TutorTurnRequest;
use Lingara\Stream\SendTutorMessageEvent;
// lingara:end

function sendTutorMessage(Client $client): void
{
    // lingara:begin sendTutorMessage
    $turn = new TutorTurnRequest(['message' => '荔枝多少钱？', 'source_lang' => 'en', 'target_lang' => 'zh', 'level' => 2]);
    foreach ($client->sendTutorMessage($turn) as $event) {
        if ($event instanceof SendTutorMessageEvent\Delta) {
            echo $event->data->getText();
        } elseif ($event instanceof SendTutorMessageEvent\Notice) {
            echo "\n(", $event->data->getMessage(), ')';
        }
    }
    echo "\n";
    // lingara:end
}
