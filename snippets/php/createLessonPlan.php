<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin createLessonPlan
use Lingara\Model\LessonPlanCreateRequest;
use Lingara\Stream\CreateLessonPlanEvent;
// lingara:end

function createLessonPlan(Client $client): void
{
    // lingara:begin createLessonPlan
    $request = new LessonPlanCreateRequest([
        'context' => 'ordering at a night market',
        'source_lang' => 'en',
        'target_lang' => 'zh',
        'level' => 2,
    ]);
    foreach ($client->createLessonPlan($request) as $event) {
        if ($event instanceof CreateLessonPlanEvent\Started) {
            echo 'plan ', $event->data->getPlanId(), "\n";
        } elseif ($event instanceof CreateLessonPlanEvent\Phase) {
            echo 'phase: ', $event->data->getPhase(), "\n";
        } elseif ($event instanceof CreateLessonPlanEvent\Result) {
            echo 'ready: ', $event->data->getPlan()->getTitle(), "\n";
        }
    }
    // lingara:end
}
