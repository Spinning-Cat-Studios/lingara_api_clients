<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin errors
use Lingara\Exception\ApiException;
use Lingara\Exception\LingaraException;
use Lingara\Exception\MaintenanceException;
use Lingara\Exception\OAuthException;
use Lingara\Exception\TransportException;
// lingara:end

function errors(Client $client): void
{
    // lingara:begin errors
    try {
        $client->getUsage();
    } catch (LingaraException $e) {
        if ($e instanceof ApiException) {
            // A refusal from the API: status, errorCode (stable) and message (localised).
            echo $e->status(), ' ', $e->errorCode(), ': ', $e->getMessage(), "\n";
            echo 'retry after: ', $e->retryAfter() ?? 'not given', "\n";
        } elseif ($e instanceof OAuthException) {
            // The token endpoint refused the credentials or the scopes.
            echo $e->status(), ' ', $e->error(), ': ', $e->description(), "\n";
        } elseif ($e instanceof MaintenanceException) {
            echo 'under maintenance; retry after: ', $e->retryAfter() ?? 'not given', "\n";
        } elseif ($e instanceof TransportException) {
            // No usable answer: connect, tls, reset, timeout, and so on.
            echo 'transport: ', $e->kind()->value, "\n";
        }
    }
    // lingara:end
}
