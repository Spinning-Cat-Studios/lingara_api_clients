<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin streamLessonPlan
use Lingara\Stream\StreamLessonPlanEvent;
// lingara:end

function streamLessonPlan(Client $client, string $planId): void
{
    // lingara:begin streamLessonPlan
    foreach ($client->streamLessonPlan($planId) as $event) {
        if ($event instanceof StreamLessonPlanEvent\Result) {
            echo 'ready: ', $event->data->getPlan()->getTitle(), "\n";
        } elseif ($event instanceof StreamLessonPlanEvent\Pending) {
            echo 'still ', $event->data->getStatus()->value, ", try again later\n";
        }
    }
    // lingara:end
}
