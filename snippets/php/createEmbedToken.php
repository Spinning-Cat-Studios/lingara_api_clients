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
    // Pass on the Lingara-Embed-Version header the player kit sent, to be
    // told when that build is out of date.
    $embedVersion = '1.1.0';
    $minted = $client->createEmbedToken(new EmbedTokenRequest([
        'player_ref' => $playerRef,
        'scopes' => ['events:read', 'embed:play'],
        'embed_version' => $embedVersion,
    ]))->value;
    // Store the subject beside the player: every event names the player by it.
    echo 'subject ', $minted->subject, "\n";
    // A notice is for you, the developer: the build is behind or deprecated.
    if ($minted->updateAvailable !== null) {
        echo 'embed ', $embedVersion, ' is out of date; deprecated: ', var_export($minted->updateAvailable->getDeprecated(), true), "\n";
    }
    // Hand the token (lgr_et_…) to the player kit. It lives 900 s and is never
    // refreshed: mint again when the kit asks.
    echo json_encode(['token' => $minted->token->exposeSecret(), 'expires_at' => $minted->expiresAt]), "\n";
    // lingara:end
}
