<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function listApiVersions(Client $client): void
{
    // lingara:begin listApiVersions
    // Needs no token: a client built without credentials can call it.
    $versions = $client->listApiVersions()->value;
    echo 'current: ', $versions->getCurrent(), "\n";
    foreach ($versions->getVersions() as $version) {
        echo $version->getId(), ' ', $version->getState()->value, "\n";
    }
    // lingara:end
}
