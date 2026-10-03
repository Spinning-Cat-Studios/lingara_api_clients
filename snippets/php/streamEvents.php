<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin streamEvents
use Lingara\Events\Generated\LessonPlanReady;
// lingara:end

function streamEvents(Client $client, string $planId): void
{
    // lingara:begin streamEvents
    // Live events, reconnecting after every ending: it never ends on its
    // own, and throws only after eight failed reconnects in a row.
    $tail = $client->tailEvents();
    foreach ($tail as $event) {
        echo $event::class, ' at ', $tail->cursor(), "\n";
        if ($event instanceof LessonPlanReady && $event->data->getPlanId() === $planId) {
            echo 'ready: ', $event->data->getTitle(), "\n";
            break; // leaving the foreach closes the connection
        }
    }
    // lingara:end
}
