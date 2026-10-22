<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function unlinkEmbedPlayer(Client $client, string $playerRef): void
{
    // lingara:begin unlinkEmbedPlayer
    // On your ordinary client holding embed:mint. A 404 embed_link_not_found
    // after a retried call means the link had already ended.
    $client->unlinkEmbedPlayer($playerRef);
    echo 'unlinked ', $playerRef, "\n";
    // lingara:end
}
