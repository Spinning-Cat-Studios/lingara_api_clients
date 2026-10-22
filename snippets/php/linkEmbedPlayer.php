<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\AccessToken;
use Lingara\Client;
use Lingara\TokenSource;

// lingara:begin linkEmbedPlayer
// The link is called with the player's own delegated lgr_at_ token: the one
// your server received when the player's Lingara account granted your app
// access (the authorization-code flow, which this library does not run yet).
// A client built from your clientId and clientSecret is refused
// 403 embed_link_needs_grant. This source hands back the same token every
// time, so a revoked grant surfaces as a 401 after one repeat.
function delegatedClient(#[\SensitiveParameter] string $accessToken, mixed ...$options): Client
{
    $source = new class (new AccessToken($accessToken)) implements TokenSource {
        public function __construct(private readonly AccessToken $token)
        {
        }

        public function token(): AccessToken
        {
            return $this->token;
        }

        public function invalidate(AccessToken $token): void
        {
        }
    };
    return new Client(...$options, tokenSource: $source);
}

function linkEmbedPlayer(Client $client, string $playerRef): void
{
    $link = $client->linkEmbedPlayer($playerRef)->value;
    // `existing` is a success too. Call again while `kept` has lesson_plans:
    // a plan still being written moves on a later call.
    echo $playerRef, ' is ', $link->getStatus()->value, ' as ', $link->getSubject(), "\n";
}
// lingara:end
