<?php

declare(strict_types=1);

namespace Lingara\Events;

/**
 * One Lingara event (ADR 30.9.26aa D3): every class under
 * Lingara\Events\Generated\ that holds an outbound type implements it, and
 * so does UnknownEvent, so `instanceof` against one class is the whole of
 * matching:
 *
 *     if ($event instanceof Generated\LessonPlanReady) { … $event->data->getPlanId() … }
 *
 * Every arm holds the envelope's fields as public readonly properties:
 * `$id` (`lgr_evt_…`, the deduplication key, since delivery is at least
 * once and unordered), `$type` (the wire type, such as `lesson_plan.ready`),
 * `$createdAt` (RFC 3339), `$apiVersion` (the version `$data` was rendered
 * at: pin the client to Version::GENERATED_FOR_VERSION) and `$subject`,
 * beside its `$data`. PHP 8.2 has no interface properties, so this
 * interface declares none.
 */
interface Event {}
