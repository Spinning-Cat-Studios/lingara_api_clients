<?php

declare(strict_types=1);

namespace LingaraSnippets;

use Lingara\Client;
// lingara:begin sendEvent
use Lingara\Events\Generated\InboundEvent;
use Lingara\Model\PlanStatus;
use Lingara\Model\WorldContextChanged;
// lingara:end

function sendEvent(Client $client): void
{
    // lingara:begin sendEvent
    $event = InboundEvent::worldContextChanged(new WorldContextChanged([
        'scene' => 'A night market after rain',
        'source_lang' => 'en',
        'target_lang' => 'zh',
        'level' => 2,
        'generate' => true,
    ]));
    // Pass idempotencyKey: to resend safely after a crash; a reused key
    // returns the first answer.
    $accepted = $client->sendEvent($event)->value;
    $reaction = $accepted->getReaction();
    if ($reaction?->getPlanStatus() === PlanStatus::GENERATING) {
        echo 'lesson_plan.ready or .failed will follow for ', $reaction->getPlanId(), "\n";
    } else {
        echo 'accepted ', $accepted->getId(), ', no event promised', "\n";
    }
    // lingara:end
}
