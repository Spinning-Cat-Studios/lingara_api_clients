<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Client;
use Lingara\Tests\Support\FakeHttpClient;
use PHPUnit\Framework\TestCase;
use Psr\Http\Message\RequestInterface;
use Psr\Http\Message\ResponseInterface;
use Psr\Log\NullLogger;

/**
 * PHP has no compiler to catch a renamed method or argument in the snippets,
 * so every snippet function runs here. snippets/php/ is not in the
 * lingara-php mirror, whose root is php/, so there this test skips itself.
 */
final class SnippetsTest extends TestCase
{
    private const DIR = __DIR__ . '/../../snippets/php';
    private const PLAN = '3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37';
    private const VERSION = '2026-09-knowing-tenpounder';

    /** 29.9.26u AC27: every snippet function runs against the in-memory fake with no error. */
    public function testEverySnippetRunsAgainstTheFake(): void
    {
        if (!is_dir(self::DIR)) {
            self::markTestSkipped('snippets/php/ is not in this checkout');
        }
        $files = glob(self::DIR . '/*.php') ?: [];
        foreach ($files as $file) {
            require_once $file;
        }
        $calls = [
            'generateVocabulary' => [], 'createLessonPlan' => [], 'getLessonPlan' => [self::PLAN],
            'streamLessonPlan' => [self::PLAN], 'sendTutorMessage' => [], 'getUsage' => [],
            'getOpenApiDocument' => [], 'listApiVersions' => [], 'getApiVersion' => [self::VERSION], 'errors' => [],
            'listEvents' => [null], 'streamEvents' => [self::PLAN], 'sendEvent' => [],
        ];
        // auth and verifyWebhook take no client: they run on their own.
        self::assertCount(count($calls) + 2, $files, 'one file per snippet');
        foreach ($calls as $function => $args) {
            $snippet = 'LingaraSnippets\\' . $function;
            ob_start();
            $snippet(self::client($function === 'errors'), ...$args);
            $out = (string) ob_get_clean();
            self::assertNotSame('', $out, $function);
        }
        $client = \LingaraSnippets\auth('lgr_cid_snippet', 'lgr_cs_snippet');
        self::assertInstanceOf(Client::class, $client);
        self::assertNotNull($client->tokenSource);
    }

    /** ADR 30.9.26aa D10: the webhook snippet accepts a delivery signed now and refuses a tampered one. */
    public function testTheWebhookSnippetVerifiesASignedDelivery(): void
    {
        if (!is_dir(self::DIR)) {
            self::markTestSkipped('snippets/php/ is not in this checkout');
        }
        require_once self::DIR . '/verifyWebhook.php';
        $key = 'conformance-webhook-secret-0001!';
        $body = json_encode(self::envelope('lesson_plan.ready'), JSON_THROW_ON_ERROR);
        $timestamp = (string) time();
        $signature = base64_encode(hash_hmac('sha256', "lgr_evt_snippet.{$timestamp}.{$body}", $key, true));
        $headers = ['Webhook-Id' => 'lgr_evt_snippet', 'Webhook-Timestamp' => $timestamp, 'Webhook-Signature' => "v1,{$signature}"];
        ob_start();
        $accepted = \LingaraSnippets\verifyWebhook('lgr_whsec_' . base64_encode($key), $body, $headers);
        $refused = \LingaraSnippets\verifyWebhook('lgr_whsec_' . base64_encode($key), "{$body} ", $headers);
        $out = (string) ob_get_clean();
        self::assertSame([204, 400], [$accepted, $refused]);
        self::assertStringContainsString(self::PLAN, $out);
    }

    private static function client(bool $refuseUsage): Client
    {
        $router = static fn(RequestInterface $request): ResponseInterface => self::answer($request, $refuseUsage);
        return new Client(
            clientId: 'lgr_cid_snippet',
            clientSecret: 'lgr_cs_snippet',
            logger: new NullLogger(),
            http: (new FakeHttpClient($router, $router, $router))->stack(),
        );
    }

    private static function answer(RequestInterface $request, bool $refuseUsage): ResponseInterface
    {
        $path = $request->getUri()->getPath();
        return match (true) {
            $path === '/oauth/token' => FakeHttpClient::token(),
            $path === '/v1/usage' && $refuseUsage => FakeHttpClient::json(403, ['code' => 'insufficient_scope', 'error' => 'needs usage:read']),
            $path === '/v1/usage' => FakeHttpClient::json(200, ['allowance' => [
                ['feature' => 'vocab', 'window' => 'day', 'limit' => 50, 'used' => 3, 'remaining' => 47],
            ]]),
            $path === '/v1/openapi.json' => FakeHttpClient::json(200, ['openapi' => '3.2.0', 'info' => ['title' => 'Lingara API', 'version' => self::VERSION]]),
            $path === '/v1/versions' => FakeHttpClient::json(200, ['current' => self::VERSION, 'development' => self::VERSION, 'versions' => [
                ['id' => self::VERSION, 'state' => 'supported', 'lts' => false, 'minted_at' => '2026-09-20T09:00:00Z'],
            ]]),
            str_starts_with($path, '/v1/versions/') => FakeHttpClient::json(200, ['id' => self::VERSION, 'state' => 'supported', 'lts' => false,
                'minted_at' => '2026-09-20T09:00:00Z', 'summary' => 'x', 'history' => [], 'spec' => ['url' => '/v1/openapi.json', 'sha256' => str_repeat('0', 64)]]),
            str_starts_with($path, '/v1/events') => self::events($request),
            default => self::lessonsAndStreams($path),
        };
    }

    private static function lessonsAndStreams(string $path): ResponseInterface
    {
        $plan = ['id' => self::PLAN, 'status' => 'complete', 'title' => 'At the night market', 'source_lang' => 'en',
            'target_lang' => 'zh', 'level' => 2, 'created_at' => '2026-09-29T10:00:00Z', 'ai_generated' => true];
        return match (true) {
            $path === '/v1/vocab/stream' => FakeHttpClient::sse(self::frames([
                'started' => ['meta' => ['level' => 2, 'source_lang' => 'en', 'target_lang' => 'zh', 'framework' => 'HSK', 'count' => 1, 'ai_generated' => true]],
                'item' => ['word' => '你好', 'translation' => 'hello'],
                'done' => new \stdClass(),
            ])),
            $path === '/v1/lesson-plans' => FakeHttpClient::sse(self::frames([
                'started' => ['plan_id' => self::PLAN], 'phase' => ['phase' => 'drafting', 'attempt' => 1], 'result' => ['plan' => $plan],
            ])),
            str_ends_with($path, '/stream') => FakeHttpClient::sse(self::frames(['pending' => ['plan_id' => self::PLAN, 'status' => 'generating']])),
            str_starts_with($path, '/v1/lesson-plans/') => FakeHttpClient::json(200, $plan),
            default => FakeHttpClient::sse(self::frames([
                'delta' => ['text' => '两斤荔枝，'], 'notice' => ['code' => 'history_trimmed', 'message' => 'Older turns were left out.'],
                'done' => new \stdClass(),
            ])),
        };
    }

    /** The events routes (ADR 30.9.26aa): one feed page, a 202, and a tail connection that delivers the plan. */
    private static function events(RequestInterface $request): ResponseInterface
    {
        return match (true) {
            $request->getMethod() === 'POST' => FakeHttpClient::json(202, ['id' => 'lgr_evt_snippet9', 'type' => 'world.context_changed',
                'created_at' => '2026-10-01T09:12:44Z', 'reaction' => ['status' => 'started', 'plan_id' => self::PLAN, 'plan_status' => 'generating']]),
            $request->getUri()->getPath() === '/v1/events/stream' => FakeHttpClient::sse("id: c1\nevent: event\ndata: "
                . json_encode(self::envelope('lesson_plan.ready'), JSON_THROW_ON_ERROR) . "\n\n"),
            default => FakeHttpClient::json(200, ['items' => [self::envelope('lesson_plan.ready'), self::envelope('lesson_plan.archived')],
                'next_cursor' => 'c2', 'has_more' => false]),
        };
    }

    /** @return array<string, mixed> */
    private static function envelope(string $type): array
    {
        return ['id' => 'lgr_evt_snippet', 'type' => $type, 'created_at' => '2026-10-01T09:12:44Z', 'api_version' => self::VERSION,
            'subject' => 'lgr_sub_snippet', 'data' => ['plan_id' => self::PLAN, 'status' => 'complete', 'title' => 'At the night market',
                'source_lang' => 'en', 'target_lang' => 'zh', 'level' => 2]];
    }

    /** @param array<string, mixed> $events */
    private static function frames(array $events): string
    {
        $body = '';
        foreach ($events as $name => $data) {
            $body .= "event: {$name}\ndata: " . json_encode($data, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE) . "\n\n";
        }
        return $body;
    }
}
