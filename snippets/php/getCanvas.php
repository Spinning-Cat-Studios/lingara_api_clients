<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function getCanvas(Client $client, string $canvasId): void
{
    // lingara:begin getCanvas
    $canvas = $client->canvases()->getCanvas($canvasId)->value;
    $draft = $canvas->getDraft();
    $active = $canvas->getActive();
    echo $canvas->getName(), ': draft ', $draft?->getDraftVersion() ?? 'none', ', active revision ', $active?->getRevision() ?? 'none', "\n";
    // lingara:end
}
