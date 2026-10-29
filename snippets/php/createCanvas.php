<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin createCanvas
use Lingara\Model\CanvasDefinition;
use Lingara\Model\CanvasEdge;
use Lingara\Model\CanvasNode;
use Lingara\Model\CanvasNodeKind;
use Lingara\Model\CanvasRecipe;
use Lingara\Model\CreateCanvasRequest;
// lingara:end

function createCanvas(Client $client): void
{
    // lingara:begin createCanvas
    $definition = new CanvasDefinition([
        'schema' => 2,
        'nodes' => [
            new CanvasNode(['id' => 'n1', 'kind' => CanvasNodeKind::CLIENT, 'position' => [0.0, 0.0]]),
            new CanvasNode(['id' => 'n2', 'kind' => CanvasNodeKind::WEBHOOK, 'position' => [1.0, 0.0]]),
        ],
        'edges' => [new CanvasEdge(['from' => 'n1', 'to' => 'n2'])],
    ]);
    $canvas = $client->canvases()->createCanvas(new CreateCanvasRequest([
        'name' => 'Tell my server when a lesson plan is ready',
        'recipe' => CanvasRecipe::LESSON_PLAN_READY,
        'definition' => $definition,
    ]))->value;
    echo 'created ', $canvas->getId(), ' at draft version ', $canvas->getDraft()?->getDraftVersion(), "\n";
    // lingara:end
}
