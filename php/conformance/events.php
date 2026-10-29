<?php

declare(strict_types=1);

// The harness's events half (ADR 30.9.26aa D9; conformance/README.md): the
// four events operations, the `events` and `tail` steps that drive
// client.events() and client.tailEvents(), and the three expect fields they
// carry; the seven canvas operations and their `page_canvases` step (ADR
// 16.10.26ak); and the seven dialogue operations, the export's reported
// object and the `page_dialogues` step (ADR 29.10.26c). Required by
// harness.php. Not in the dist.

use Lingara\ApiResponse;
use Lingara\Client;
use Lingara\Dialogue\ExportedDialogue;
use Lingara\Dialogue\ExportedDialogue\Body;
use Lingara\Dialogue\ExportedDialogue\NotModified;
use Lingara\Events\Event;
use Lingara\Events\Generated\InboundEvent;
use Lingara\Events\UnknownEvent;
use Lingara\Exception\LingaraException;
use Lingara\Model\ActivateCanvasRequest;
use Lingara\Model\ActivateDialogueRequest;
use Lingara\Model\CanvasPlan;
use Lingara\Model\CreateCanvasRequest;
use Lingara\Model\CreateDialogueRequest;
use Lingara\Model\RenameCanvasRequest;
use Lingara\Model\RenameDialogueRequest;
use Lingara\Model\SaveCanvasDraftRequest;
use Lingara\Model\SaveDialogueDraftRequest;
use Lingara\Model\WorldContextChanged;
use Lingara\Model\WorldPracticeRequested;
use Lingara\ObjectSerializer;

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

/**
 * One canvas operation of a `call` step (ADR 16.10.26ak), its body built as
 * the operation's request model, or null when the operation is not one.
 */
function invokeCanvas(Client $client, array $call): ?array
{
    $canvases = $client->canvases();
    $id = (string) ($call['params']['canvas_id'] ?? '');
    $body = $call['body'] ?? [];
    return match ($call['operation']) {
        'listCanvases' => completed($canvases->listCanvases($call['params'] ?? [])),
        'createCanvas' => completed($canvases->createCanvas(requestModel($body, CreateCanvasRequest::class)), 201),
        'getCanvas' => completed($canvases->getCanvas($id)),
        'renameCanvas' => completed($canvases->renameCanvas($id, requestModel($body, RenameCanvasRequest::class))),
        'saveCanvasDraft' => completed($canvases->saveCanvasDraft($id, requestModel($body, SaveCanvasDraftRequest::class))),
        'activateCanvas' => completed($canvases->activateCanvas($id, requestModel($body, ActivateCanvasRequest::class))),
        'dryRunCanvasPlan' => completed($canvases->dryRunCanvasPlan(requestModel($body, CanvasPlan::class))),
        default => null,
    };
}

/**
 * One dialogue operation of a `call` step (ADR 29.10.26c), its body built as
 * the operation's request model, or null when the operation is not one.
 */
function invokeDialogue(Client $client, array $call): ?array
{
    $dialogues = $client->dialogues();
    $params = $call['params'] ?? [];
    $id = (string) ($params['dialogue_id'] ?? '');
    $body = $call['body'] ?? [];
    return match ($call['operation']) {
        'listDialogues' => completed($dialogues->listDialogues($params)),
        'createDialogue' => completed($dialogues->createDialogue(requestModel($body, CreateDialogueRequest::class)), 201),
        'getDialogue' => completed($dialogues->getDialogue($id)),
        'renameDialogue' => completed($dialogues->renameDialogue($id, requestModel($body, RenameDialogueRequest::class))),
        'saveDialogueDraft' => completed($dialogues->saveDialogueDraft($id, requestModel($body, SaveDialogueDraftRequest::class))),
        'activateDialogue' => completed($dialogues->activateDialogue($id, requestModel($body, ActivateDialogueRequest::class))),
        'exportDialogue' => exported($dialogues->exportDialogue($id, $params['revision'] ?? null, $params['layout'] ?? null, $params['if_none_match'] ?? null)),
        default => null,
    };
}

/**
 * An export as the harness reports it (ADR 29.10.26c D7): `{revision, etag,
 * ir_json}` for a Body, `{not_modified: true, revision, etag}` for a
 * NotModified.
 */
function exported(ExportedDialogue $answer): array
{
    $body = match (true) {
        $answer instanceof Body => ['revision' => $answer->revision, 'etag' => $answer->etag, 'ir_json' => $answer->irJson],
        $answer instanceof NotModified => ['not_modified' => true, 'revision' => $answer->revision, 'etag' => $answer->etag],
        default => throw new InvalidArgumentException('harness: no export arm ' . $answer::class),
    };
    return [
        'outcome' => 'completed', 'status' => $answer instanceof Body ? 200 : 304, 'body' => $body,
        'served_version' => $answer->servedVersion, 'renderings' => renderings($answer),
    ];
}

/** A `page_dialogues` step (ADR 29.10.26c D7): the helper iterated to its end, its items as one list under `body`. */
function runPageDialogues(Client $client, array $step): array
{
    $items = [];
    try {
        foreach ($client->dialogues()->pageDialogues($step['page_dialogues']['params'] ?? []) as $item) {
            $items[] = plain($item);
        }
        return ['outcome' => 'completed', 'body' => $items];
    } catch (LingaraException $e) {
        return failed($e, []);
    }
}

/**
 * A case body as its request model, nested models and enums included,
 * through the serializer: a key the case leaves out stays unset, so it is
 * not sent.
 *
 * @template T of object
 *
 * @param class-string<T> $class
 *
 * @return T
 */
function requestModel(array $body, string $class): object
{
    return ObjectSerializer::deserialize(json_decode((string) json_encode($body), false), $class);
}

/** A `page_canvases` step (ADR 16.10.26ak D7): the helper iterated to its end, its items as one list under `body`. */
function runPageCanvases(Client $client, array $step): array
{
    $items = [];
    try {
        foreach ($client->canvases()->pageCanvases($step['page_canvases']['params'] ?? []) as $item) {
            $items[] = plain($item);
        }
        return ['outcome' => 'completed', 'body' => $items];
    } catch (LingaraException $e) {
        return failed($e, []);
    }
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
