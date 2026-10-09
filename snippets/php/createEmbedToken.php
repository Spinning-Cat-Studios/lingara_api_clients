<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin createEmbedToken
use Lingara\Model\EmbedTokenRequest;
// lingara:end

function createEmbedToken(Client $client, string $playerRef): void
{
    // lingara:begin createEmbedToken
    // On your server, from a metered client holding embed:mint whose
    // credentials come from the environment, never on the player's device.
    $minted = $client->createEmbedToken(new EmbedTokenRequest([
        'player_ref' => $playerRef,
        'scopes' => ['events:read', 'embed:play'],
    ]))->value;
    // Store the subject beside the player: every event names the player by it.
    echo 'subject ', $minted->subject, "\n";
    // Hand the token (lgr_et_…) to the player kit. It lives 900 s and is never
    // refreshed: mint again when the kit asks.
    echo json_encode(['token' => $minted->token->exposeSecret(), 'expires_at' => $minted->expiresAt]), "\n";
    // lingara:end
}
