<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;

function getLessonPlan(Client $client, string $planId): void
{
    // lingara:begin getLessonPlan
    $plan = $client->getLessonPlan($planId)->value;
    echo $plan->getTitle(), ' (', $plan->getStatus()->value, ")\n";
    // lingara:end
}
