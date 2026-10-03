<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin listEvents
use Lingara\Events\Generated\LessonPlanFailed;
use Lingara\Events\Generated\LessonPlanReady;
use Lingara\Events\UnknownEvent;
// lingara:end

/** @return string|null the cursor to pass next time */
function listEvents(Client $client, ?string $cursor): ?string
{
    // lingara:begin listEvents
    // Every event after the saved cursor, to the end of the feed; with no
    // cursor, from now (or pass start: 'oldest').
    $feed = $client->events(cursor: $cursor);
    foreach ($feed as $event) {
        if ($event instanceof LessonPlanReady) {
            echo 'ready: ', $event->data->getPlanId(), "\n";
        } elseif ($event instanceof LessonPlanFailed) {
            echo 'failed: ', $event->data->getPlanId(), "\n";
        } elseif ($event instanceof UnknownEvent) {
            echo 'a type this library does not know: ', $event->type, "\n";
        }
    }
    $cursor = $feed->cursor(); // save it, and pass it next time
    // lingara:end
    return $cursor;
}
