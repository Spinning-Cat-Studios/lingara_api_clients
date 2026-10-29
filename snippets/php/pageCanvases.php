<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function pageCanvases(Client $client): void
{
    // lingara:begin pageCanvases
    // Every page, fetched as you iterate; the filters are resent on each one.
    foreach ($client->canvases()->pageCanvases(['kinds' => ['webhook'], 'limit' => 10]) as $canvas) {
        echo $canvas->getId(), ' ', $canvas->getName(), "\n";
    }
    // lingara:end
}
