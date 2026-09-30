<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function getApiVersion(Client $client, string $versionId): void
{
    // lingara:begin getApiVersion
    // Needs no token: a client built without credentials can call it.
    $version = $client->getApiVersion($versionId)->value;
    echo $version->getId(), ' is ', $version->getState()->value, "\n";
    // lingara:end
}
