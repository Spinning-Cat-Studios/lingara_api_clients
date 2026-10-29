<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin saveCanvasDraft
use Lingara\Exception\ApiException;
use Lingara\Model\CanvasDefinition;
use Lingara\Model\SaveCanvasDraftRequest;
// lingara:end

function saveCanvasDraft(Client $client, string $canvasId): void
{
    // lingara:begin saveCanvasDraft
    $canvases = $client->canvases();
    $draft = $canvases->getCanvas($canvasId)->value->getDraft();
    if ($draft === null) {
        return;
    }
    // Edit the definition you read: keys this library does not know are kept.
    $definition = $draft->getDefinition();
    try {
        $saved = $canvases->saveCanvasDraft($canvasId, new SaveCanvasDraftRequest([
            'definition' => $definition,
            'draft_version' => $draft->getDraftVersion(),
        ]))->value;
        echo 'saved at draft version ', $saved->getDraft()?->getDraftVersion(), "\n";
    } catch (ApiException $e) {
        if ($e->errorCode() !== 'canvas_conflict') {
            throw $e;
        }
        // Someone saved first, or this was your own retried save: never
        // retried for you. Re-read, compare, then decide whose draft wins.
        $theirs = $canvases->getCanvas($canvasId)->value->getDraft();
        $same = $theirs !== null && sameDefinition($theirs->getDefinition(), $definition);
        echo $same ? 'already saved' : 'changed elsewhere at draft version ' . $theirs?->getDraftVersion(), "\n";
    }
    // lingara:end
}

// lingara:begin saveCanvasDraft
/** Two definitions, compared as the JSON they send. */
function sameDefinition(CanvasDefinition $a, CanvasDefinition $b): bool
{
    return json_encode($a) === json_encode($b);
}
// lingara:end
