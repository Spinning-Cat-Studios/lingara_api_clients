<?php

declare(strict_types=1);

// The harness's events half (ADR 30.9.26aa D9; conformance/README.md): the
// four events operations, the `events` and `tail` steps that drive
// client.events() and client.tailEvents(), and the three expect fields they
// carry. Required by harness.php. Not in the dist.

use Lingara\ApiResponse;
use Lingara\Client;
use Lingara\Events\Event;
use Lingara\Events\Generated\InboundEvent;
use Lingara\Events\UnknownEvent;
use Lingara\Exception\LingaraException;
use Lingara\Model\WorldContextChanged;
use Lingara\Model\WorldPracticeRequested;

/** One events operation of a `call` step, or null when the operation is not one. */
function invokeEvents(Client $client, array $call): ?array
{
    $params = $call['params'] ?? [];
    $cursor = $params['cursor'] ?? null;
    $start = $params['start'] ?? null;
    $types = $params['types'] ?? null;
    return match ($call['operation']) {
        'listEvents' => completed($client->listEvents($cursor, $start, $types, $params['limit'] ?? null)),
        'sendEvent' => completed($client->sendEvent(inboundEvent($call['body'] ?? []), $call['idempotency_key'] ?? null), 202),
        'streamEvents' => consume($client->streamEvents($cursor, $start, $types), 'streamEvents', $call['cancel_after_events'] ?? null),
        'getAsyncApiDocument' => completed($client->getAsyncApiDocument()),
        default => null,
    };
}

/** A case's `{type, data}` body as InboundEvent, through its public constructors. */
function inboundEvent(array $body): InboundEvent
{
    return match ($body['type'] ?? null) {
        'world.context_changed' => InboundEvent::worldContextChanged(new WorldContextChanged($body['data'])),
        'world.practice_requested' => InboundEvent::worldPracticeRequested(new WorldPracticeRequested($body['data'])),
        default => throw new InvalidArgumentException('harness: no inbound type ' . json_encode($body['type'] ?? null)),
    };
}

/**
 * An `events` or `tail` step: iterate the helper (a tail until `take`
 * events, then leave the foreach, which closes it) and record each yielded
 * envelope's id, each UnknownEvent's type and the final cursor.
 */
function runHelper(Client $client, array $step): array
{
    $tail = isset($step['tail']);
    $options = $tail ? $step['tail'] : $step['events'];
    $args = [$options['cursor'] ?? null, $options['start'] ?? null, $options['types'] ?? null];
    $helper = $tail ? $client->tailEvents(...$args) : $client->events(...$args);
    $seen = ['ids' => [], 'unknown' => []];
    try {
        foreach ($helper as $event) {
            $seen = record($seen, $event);
            if ($tail && count($seen['ids']) === $options['take']) {
                break;
            }
        }
        $outcome = ['outcome' => 'completed'];
    } catch (LingaraException $e) {
        $outcome = failed($e, []);
    }
    return $outcome + ['event_ids' => $seen['ids'], 'unknown_types' => $seen['unknown'], 'cursor' => $helper->cursor()];
}

function record(array $seen, Event $event): array
{
    $fields = get_object_vars($event);
    $seen['ids'][] = $fields['id'];
    if ($event instanceof UnknownEvent) {
        $seen['unknown'][] = $event->type;
    }
    return $seen;
}

/** @return list<string> the three helper fields' mismatches */
function compareHelper(array $expect, array $seen): array
{
    $out = [];
    foreach (['event_ids', 'unknown_types'] as $label) {
        if (array_key_exists($label, $expect) && canon($expect[$label]) !== canon($seen[$label] ?? [])) {
            $out[] = "{$label}: expected " . canon($expect[$label]) . ', got ' . canon($seen[$label] ?? []);
        }
    }
    if (isset($expect['cursor']) && !matches($expect['cursor'], $seen['cursor'] ?? null)) {
        $out[] = 'cursor: expected ' . canon($expect['cursor']) . ', got ' . json_encode($seen['cursor'] ?? null);
    }
    return $out;
}

/** The case format's string matcher. */
function matches(array $matcher, ?string $value): bool
{
    return match (true) {
        isset($matcher['absent']) => $value === null,
        $value === null => false,
        isset($matcher['equals']) => $value === $matcher['equals'],
        isset($matcher['prefix']) => str_starts_with($value, $matcher['prefix']),
        isset($matcher['contains']) => str_contains($value, $matcher['contains']),
        isset($matcher['pattern']) => preg_match('/' . str_replace('/', '\/', $matcher['pattern']) . '/', $value) === 1,
        default => false,
    };
}
