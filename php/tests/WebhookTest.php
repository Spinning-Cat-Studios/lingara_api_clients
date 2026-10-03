<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Events\Event;
use Lingara\Events\Generated\LessonPlanReady;
use Lingara\Events\UnknownEvent;
use Lingara\Events\VerificationException;
use Lingara\Events\Webhook;
use Lingara\Exception\LingaraException;
use Lingara\Tests\Support\FakeClock;
use Nyholm\Psr7\ServerRequest;
use PHPUnit\Framework\TestCase;

/**
 * The webhook verifier against the shared vectors (CONTRACT.md appendix W;
 * ADR 30.9.26aa D4, D5). conformance/ is not in the lingara-php mirror,
 * whose root is php/, so there the vector tests skip themselves.
 */
final class WebhookTest extends TestCase
{
    private const VECTORS = __DIR__ . '/../../conformance/vectors/webhook-signatures.json';

    /**
     * 30.9.26aa AC29: every shared vector gives its expected result from
     * verify(), with the vector's `now` through the clock seam: the event's
     * id and type (an UnknownEvent where the vector says so), the one
     * failure reason, or a construction refusal.
     *
     * 30.9.26aa AC45: verifySignature() passes every `ok` and
     * `malformed_payload` vector, whose signatures match, and raises every
     * other `error` vector's own reason.
     */
    public function testEverySharedVectorVerifiesAsExpected(): void
    {
        $vectors = self::vectors();
        self::assertGreaterThanOrEqual(28, count($vectors));
        foreach ($vectors as $vector) {
            $name = $vector['name'];
            $expect = $vector['expect'];
            try {
                $webhook = new Webhook($vector['secrets'], new FakeClock((float) $vector['now']));
            } catch (\InvalidArgumentException) {
                self::assertSame(['refused' => true], $expect, "{$name}: refused at construction");
                continue;
            }
            self::assertArrayNotHasKey('refused', $expect, "{$name}: constructed");
            self::assertSame(self::outcome(static fn(): mixed => $webhook->verify($vector['body'], $vector['headers'])), $expect, $name);
            $signature = isset($expect['ok']) || ($expect['error'] ?? null) === VerificationException::MALFORMED_PAYLOAD
                ? ['ok' => null]
                : $expect;
            $verifySignature = static function () use ($webhook, $vector): mixed {
                $webhook->verifySignature($vector['body'], $vector['headers']);
                return null;
            };
            self::assertSame($signature, self::outcome($verifySignature), "{$name}: verifySignature");
        }
    }

    /**
     * 30.9.26aa D10: any PSR-7 message serves as the headers, read
     * case-insensitively, so a framework's request goes in as it is.
     */
    public function testAPsr7MessageWorksAsHeaders(): void
    {
        $vector = self::vector('mixed-case-header-names');
        $webhook = new Webhook($vector['secrets'], new FakeClock((float) $vector['now']));
        $request = new ServerRequest('POST', '/webhooks/lingara', $vector['headers'], $vector['body']);

        $event = $webhook->verify((string) $request->getBody(), $request);
        self::assertInstanceOf(LessonPlanReady::class, $event);
        self::assertSame('lgr_evt_conformance1', $event->id);
        self::assertSame('3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37', $event->data->getPlanId());
        $webhook->verifySignature((string) $request->getBody(), $request);

        $unsigned = $request->withoutHeader('webhook-signature');
        $this->expectExceptionObject(new VerificationException(VerificationException::MISSING_HEADER, 'no webhook-signature header'));
        $webhook->verify((string) $unsigned->getBody(), $unsigned);
    }

    /**
     * ADR 30.9.26aa D4: the error sits outside the LingaraException family,
     * its message holds no secret, signature or body, and no rendering of a
     * Webhook shows a secret.
     */
    public function testTheErrorIsOutsideTheFamilyAndNothingSecretRenders(): void
    {
        self::assertNotContains(LingaraException::class, class_implements(VerificationException::class));
        $vector = self::vector('tampered-body');
        $secret = $vector['secrets'][0];
        $webhook = new Webhook($vector['secrets'], new FakeClock((float) $vector['now']));
        try {
            $webhook->verify($vector['body'], $vector['headers']);
            self::fail('a tampered body verified');
        } catch (VerificationException $e) {
            self::assertSame(VerificationException::NO_MATCHING_SIGNATURE, $e->reason());
            $signature = substr($vector['headers']['webhook-signature'], 3);
            foreach ([$signature, $vector['body'], $secret] as $hidden) {
                self::assertStringNotContainsString($hidden, $e->getMessage());
            }
        }
        ob_start();
        var_dump($webhook);
        $renderings = [(string) ob_get_clean(), print_r($webhook, true), (string) json_encode($webhook), (string) @var_export($webhook, true)];
        $encoded = substr($secret, strlen('lgr_whsec_'));
        foreach ($renderings as $rendering) {
            self::assertStringNotContainsString($encoded, $rendering);
            self::assertStringNotContainsString((string) base64_decode($encoded, true), $rendering);
        }
    }

    /**
     * What verify() or verifySignature() did, in the vectors' own words.
     *
     * @param \Closure(): mixed $call
     *
     * @return array<string, mixed>
     */
    private static function outcome(\Closure $call): array
    {
        try {
            $event = $call();
        } catch (VerificationException $e) {
            return ['error' => $e->reason()];
        }
        if (!$event instanceof Event) {
            return ['ok' => null];
        }
        $fields = get_object_vars($event);
        $ok = ['id' => $fields['id'] ?? null, 'type' => $fields['type'] ?? null];
        return ['ok' => $event instanceof UnknownEvent ? $ok + ['unknown' => true] : $ok];
    }

    /** @return array{name: string, secrets: list<string>, headers: array<string, string>, body: string, now: int, expect: array<string, mixed>} */
    private static function vector(string $name): array
    {
        foreach (self::vectors() as $vector) {
            if ($vector['name'] === $name) {
                return $vector;
            }
        }
        self::fail("no vector {$name}");
    }

    /** @return list<array{name: string, secrets: list<string>, headers: array<string, string>, body: string, now: int, expect: array<string, mixed>}> */
    private static function vectors(): array
    {
        if (!is_file(self::VECTORS)) {
            self::markTestSkipped('conformance/vectors/ is not in this checkout');
        }
        $file = json_decode((string) file_get_contents(self::VECTORS), true, 512, JSON_THROW_ON_ERROR);
        self::assertIsArray($file);
        self::assertIsArray($file['vectors'] ?? null);
        /** @var list<array{name: string, secrets: list<string>, headers: array<string, string>, body: string, now: int, expect: array<string, mixed>}> */
        return $file['vectors'];
    }
}
