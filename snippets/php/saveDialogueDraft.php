<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin saveDialogueDraft
use Lingara\Exception\ApiException;
use Lingara\Model\SaveDialogueDraftRequest;
// lingara:end

function saveDialogueDraft(Client $client, string $dialogueId, object $definition, int $draftVersion): void
{
    // lingara:begin saveDialogueDraft
    $dialogues = $client->dialogues();
    try {
        $saved = $dialogues->saveDialogueDraft($dialogueId, new SaveDialogueDraftRequest([
            'definition' => $definition,
            'draft_version' => $draftVersion,
        ]))->value;
        echo 'saved; the next save sends draft version ', $saved->getDraft()?->getDraftVersion(), "\n";
    } catch (ApiException $e) {
        if ($e->errorCode() !== 'dialogue_conflict') {
            throw $e;
        }
        // The draft moved since you read it, perhaps by your own retried
        // save. It is never retried for you: re-read, compare, and decide.
        // With no draft at all, send draft version 0 to open one.
        $current = $dialogues->getDialogue($dialogueId)->value->getDraft();
        echo 'changed elsewhere; the draft is now at version ', $current?->getDraftVersion() ?? 0, "\n";
    }
    // lingara:end
}
