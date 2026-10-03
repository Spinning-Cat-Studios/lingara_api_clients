<?php

declare(strict_types=1);

namespace LingaraSnippets;

// lingara:begin verifyWebhook
use Lingara\Events\Generated\LessonPlanReady;
use Lingara\Events\UnknownEvent;
use Lingara\Events\VerificationException;
use Lingara\Events\Webhook;
// lingara:end

/**
 * @param array<string, string> $headers
 *
 * @return int the status to answer with
 */
function verifyWebhook(string $secret, string $body, array $headers): int
{
    // lingara:begin verifyWebhook
    // The raw body, never a parsed one: $body = file_get_contents('php://input');
    // and $headers = getallheaders(), or pass a PSR-7 request as the headers.
    $webhook = new Webhook($secret);
    try {
        $event = $webhook->verify($body, $headers);
    } catch (VerificationException $e) {
        echo 'refused: ', $e->reason(), "\n";
        return 400;
    }
    // Answer 2xx fast and deduplicate by id: a delivery can arrive twice.
    if ($event instanceof LessonPlanReady) {
        echo 'plan ready: ', $event->data->getPlanId(), ' (', $event->id, ")\n";
    } elseif ($event instanceof UnknownEvent) {
        echo 'a type this library does not know: ', $event->type, "\n";
    }
    return 204;
    // lingara:end
}
