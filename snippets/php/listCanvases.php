<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function listCanvases(Client $client): void
{
    // lingara:begin listCanvases
    // One page; pageCanvases() walks them all. Lists are sent comma-separated.
    $page = $client->canvases()->listCanvases(['limit' => 20, 'status' => 'live', 'kinds' => ['webhook']])->value;
    foreach ($page->getItems() as $canvas) {
        echo $canvas->getName(), ' (', $canvas->getRecipe()->value, ', ', $canvas->getNodeCount(), " nodes)\n";
    }
    echo $page->getHasMore() ? "more after {$page->getNextCursor()}\n" : "that is all\n";
    // lingara:end
}
