<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function getOpenApiDocument(Client $client): void
{
    // lingara:begin getOpenApiDocument
    // Needs no token: a client built without credentials can call it.
    $document = $client->getOpenApiDocument()->value;
    echo $document->info->title, ' ', $document->info->version, "\n";
    // lingara:end
}
