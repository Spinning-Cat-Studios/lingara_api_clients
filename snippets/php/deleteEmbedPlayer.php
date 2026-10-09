<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function deleteEmbedPlayer(Client $client, string $playerRef): void
{
    // lingara:begin deleteEmbedPlayer
    // Deletes the player and revokes its tokens. An unknown player is a
    // success too, so the call is safe to repeat.
    $client->deleteEmbedPlayer($playerRef);
    echo 'deleted ', $playerRef, "\n";
    // lingara:end
}
