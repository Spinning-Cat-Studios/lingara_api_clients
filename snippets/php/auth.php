<?php

declare(strict_types=1);

// The PHP examples the documentation site shows. Each marked region is
// vendored at a released tag; php/tests/SnippetsTest.php runs every function
// here against the unit-test fake. A `use` line is legal only at namespace
// scope, so a key that shows one has two regions: the `use` lines, then the
// call.

namespace LingaraSnippets;

// lingara:begin auth
use Lingara\Client;
// lingara:end

function auth(string $clientId, string $clientSecret): Client
{
    // lingara:begin auth
    $client = new Client(clientId: $clientId, clientSecret: $clientSecret);
    // lingara:end
    return $client;
}
