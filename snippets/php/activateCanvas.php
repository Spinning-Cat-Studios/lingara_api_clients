<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin activateCanvas
use Lingara\Model\ActivateCanvasRequest;
use Lingara\Model\CanvasActivationOutcome;
use Lingara\Model\CanvasObjectRef;
// lingara:end

function activateCanvas(Client $client, string $canvasId, int $draftVersion): void
{
    // lingara:begin activateCanvas
    // After applying the draft's plan: each node's handle and the object it
    // made. A replay at a draft version already promoted is 409
    // canvas_conflict and writes nothing.
    $canvas = $client->canvases()->activateCanvas($canvasId, new ActivateCanvasRequest([
        'draft_version' => $draftVersion,
        'outcome' => CanvasActivationOutcome::COMPLETE,
        'objects' => ['n1' => new CanvasObjectRef(['id' => '0b9e4c2d-7a1f-4e3b-9d8c-5f6a7b8c9d0e'])],
    ]))->value;
    echo 'active revision ', $canvas->getActive()?->getRevision(), "\n";
    // lingara:end
}
