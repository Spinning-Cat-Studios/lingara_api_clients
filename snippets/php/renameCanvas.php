<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin renameCanvas
use Lingara\Model\RenameCanvasRequest;
// lingara:end

function renameCanvas(Client $client, string $canvasId): void
{
    // lingara:begin renameCanvas
    $canvas = $client->canvases()->renameCanvas($canvasId, new RenameCanvasRequest(['name' => 'Ready plans to my server']))->value;
    echo 'renamed to ', $canvas->getName(), "\n";
    // lingara:end
}
