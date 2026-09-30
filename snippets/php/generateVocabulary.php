<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin generateVocabulary
use Lingara\Model\VocabRequest;
use Lingara\Stream\GenerateVocabularyEvent;
// lingara:end

function generateVocabulary(Client $client): void
{
    // lingara:begin generateVocabulary
    $request = new VocabRequest(['level' => 2, 'source_lang' => 'en', 'target_lang' => 'zh', 'count' => 8]);
    foreach ($client->generateVocabulary($request) as $event) {
        if ($event instanceof GenerateVocabularyEvent\Item) {
            echo $event->data->getWord(), ': ', $event->data->getTranslation(), "\n";
        }
    }
    // lingara:end
}
