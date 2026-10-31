<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Client;
use Lingara\Tests\Support\FakeHttpClient;
use Nyholm\Psr7\Response;
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
    private const CANVAS = '6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b';
    private const DIALOGUE = '4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71';
    private const ETAG = '"3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e"';

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
            'createEmbedToken' => ['player-1001'], 'deleteEmbedPlayer' => ['guild/42'], 'sendDialogueTurn' => [],
            'unlinkEmbedPlayer' => ['guild/42'],
            'listCanvases' => [], 'pageCanvases' => [], 'createCanvas' => [], 'getCanvas' => [self::CANVAS],
            'renameCanvas' => [self::CANVAS], 'saveCanvasDraft' => [self::CANVAS], 'activateCanvas' => [self::CANVAS, 4],
            'dryRunCanvasPlan' => [],
            'listDialogues' => [], 'pageDialogues' => [], 'createDialogue' => [], 'getDialogue' => [self::DIALOGUE],
            'renameDialogue' => [self::DIALOGUE], 'saveDialogueDraft' => [self::DIALOGUE, json_decode('{"schema":"dialogue.v1"}'), 4],
            'activateDialogue' => [self::DIALOGUE], 'exportDialogue' => [self::DIALOGUE, ['etag' => self::ETAG, 'irJson' => '{}']],
        ];
        // auth and verifyWebhook take no client, and linkEmbedPlayer takes
        // delegatedClient's (ADR 22.10.26t D7): they run on their own.
        self::assertCount(count($calls) + 3, $files, 'one file per snippet');
        ob_start();
        \LingaraSnippets\linkEmbedPlayer(self::delegated(), 'guild/42');
        self::assertStringContainsString('linked as lgr_sub_account', (string) ob_get_clean());
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
            // Four answers: the token, then saveCanvasDraft's read, save and re-read.
            http: (new FakeHttpClient($router, $router, $router, $router))->stack(),
        );
    }

    /** The link snippet's client: its delegated token, and a router that refuses any other bearer. */
    private static function delegated(): Client
    {
        $router = static fn(RequestInterface $request): ResponseInterface => $request->getHeaderLine('Authorization') === 'Bearer lgr_at_delegated'
            ? self::answer($request, false)
            : FakeHttpClient::json(403, ['code' => 'embed_link_needs_grant', 'error' => 'not the delegated token']);
        return \LingaraSnippets\delegatedClient('lgr_at_delegated', logger: new NullLogger(), http: (new FakeHttpClient($router, $router, $router))->stack());
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
            str_starts_with($path, '/v1/versions') => self::versions($path),
            str_starts_with($path, '/v1/events') => self::events($request),
            str_starts_with($path, '/v1/embed/') => self::embed($path, $request->getMethod()),
            preg_match('#^/v1/(canvases|dialogues)#', $path) === 1 => self::objectGroups($request),
            default => self::lessonsAndStreams($path),
        };
    }

    /**
     * The embed routes (ADR 1.10.26w D9): a mint, an empty 204 for a delete,
     * and an NPC turn; a link for a PUT to /link (ADR 22.10.26t), and an
     * empty 204 for its DELETE.
     */
    private static function embed(string $path, string $method): ResponseInterface
    {
        return match (true) {
            str_ends_with($path, '/link') && $method === 'PUT' => FakeHttpClient::json(200, ['status' => 'linked', 'subject' => 'lgr_sub_account',
                'previous_subject' => 'lgr_sub_snippet', 'linked_at' => '2026-11-02T18:04:11Z', 'scopes' => ['embed:play'],
                'carried' => new \stdClass(), 'kept' => new \stdClass()]),
            $path === '/v1/embed/tokens' => FakeHttpClient::json(200, ['token' => 'lgr_et_snippet', 'expires_at' => '2026-10-01T09:27:44Z',
                'expires_in' => 900, 'subject' => 'lgr_sub_snippet', 'scopes' => ['events:read', 'embed:play'], 'account_linked' => false,
                'update_available' => ['latest' => '1.2.0', 'deprecated' => true, 'message' => null]]),
            str_starts_with($path, '/v1/embed/players/') => new Response(204),
            default => FakeHttpClient::sse(self::frames([
                'delta' => ['text' => '十块钱，一盘。'], 'notice' => ['code' => 'history_trimmed', 'message' => 'Older lines were left out.'],
                'done' => new \stdClass(),
            ])),
        };
    }

    /** The version list, or one version. */
    private static function versions(string $path): ResponseInterface
    {
        return $path === '/v1/versions'
            ? FakeHttpClient::json(200, ['current' => self::VERSION, 'development' => self::VERSION, 'versions' => [
                ['id' => self::VERSION, 'state' => 'supported', 'lts' => false, 'minted_at' => '2026-09-20T09:00:00Z'],
            ]])
            : FakeHttpClient::json(200, ['id' => self::VERSION, 'state' => 'supported', 'lts' => false,
                'minted_at' => '2026-09-20T09:00:00Z', 'summary' => 'x', 'history' => [], 'spec' => ['url' => '/v1/openapi.json', 'sha256' => str_repeat('0', 64)]]);
    }

    /** The canvas (ADR 16.10.26ak) and dialogue (ADR 29.10.26c) groups' routes. */
    private static function objectGroups(RequestInterface $request): ResponseInterface
    {
        $path = $request->getUri()->getPath();
        return str_starts_with($path, '/v1/canvases') ? self::canvases($path, $request->getMethod()) : self::dialogues($request);
    }

    /**
     * The canvas routes (ADR 16.10.26ak): a one-item page, a dry-run with a
     * refused op, and a canvas for the rest; a draft save answers 409
     * canvas_conflict, so the snippet's conflict branch runs.
     */
    private static function canvases(string $path, string $method): ResponseInterface
    {
        $definition = ['schema' => 2, 'nodes' => [['id' => 'n1', 'kind' => 'client', 'position' => [0.5, 1.5], 'fields' => ['name' => 'Lesson server']]]];
        $canvas = ['id' => self::CANVAS, 'name' => 'Lesson plan webhook', 'created_at' => '2026-10-20T09:00:00Z', 'updated_at' => '2026-10-20T09:00:00Z',
            'draft' => ['revision' => 3, 'draft_version' => 4, 'definition' => $definition, 'objects' => new \stdClass()],
            'active' => ['revision' => 2, 'definition' => $definition, 'objects' => ['n1' => ['id' => self::PLAN]], 'activated_at' => '2026-10-21T10:30:00Z']];
        $item = ['id' => self::CANVAS, 'name' => 'Lesson plan webhook', 'recipe' => 'lesson_plan_ready', 'created_at' => '2026-10-20T09:00:00Z',
            'updated_at' => '2026-10-20T09:00:00Z', 'has_draft' => true, 'active_revision' => 2, 'node_count' => 1, 'node_kinds' => ['client'],
            'scopes' => ['events:read'], 'objects' => [['handle' => 'n1', 'kind' => 'client', 'id' => self::PLAN]]];
        return match (true) {
            $path === '/v1/canvases' && $method === 'GET' => FakeHttpClient::json(200, ['items' => [$item], 'total' => 1, 'next_cursor' => null, 'has_more' => false]),
            $path === '/v1/canvases/dry-run' => FakeHttpClient::json(200, ['ok' => false, 'verdicts' => [
                ['handle' => 'c1', 'kind' => 'client', 'op' => 'create', 'verdict' => 'refused', 'code' => 'canvas_limit_reached', 'error' => 'Too many clients.'],
            ]]),
            str_ends_with($path, '/draft') => FakeHttpClient::json(409, ['code' => 'canvas_conflict', 'error' => 'This canvas changed since you opened it.']),
            default => FakeHttpClient::json($path === '/v1/canvases' ? 201 : 200, $canvas),
        };
    }

    /**
     * The dialogue routes (ADR 29.10.26c): a one-item page, a 304 for an
     * export sent If-None-Match and its bytes otherwise, and a dialogue for
     * the rest; a draft save answers 409 dialogue_conflict, so the snippet's
     * conflict branch runs.
     */
    private static function dialogues(RequestInterface $request): ResponseInterface
    {
        $path = $request->getUri()->getPath();
        $definition = ['schema' => 'dialogue.v1', 'id' => 'night_market_keepers', 'start' => 'greet', 'npcs' => [['id' => 'old_wei', 'name' => 'Old Wei']],
            'checkpoints' => [['id' => 'greet', 'goal' => 'Greet the stranger', 'may_disclose' => [], 'edges' => []]], 'facts' => []];
        $dialogue = ['id' => self::DIALOGUE, 'name' => 'Night market', 'created_at' => '2026-10-28T09:00:00Z', 'updated_at' => '2026-10-28T10:30:00Z',
            'draft' => ['revision' => 3, 'draft_version' => 4, 'definition' => $definition],
            'active' => ['revision' => 2, 'definition' => $definition, 'activated_at' => '2026-10-28T10:30:00Z'],
            'warnings' => [['code' => 'checkpoint_unreachable', 'path' => '/checkpoints/0']]];
        $item = ['id' => self::DIALOGUE, 'name' => 'Night market', 'ir_id' => 'night_market_keepers', 'created_at' => '2026-10-28T09:00:00Z',
            'updated_at' => '2026-10-28T10:30:00Z', 'has_draft' => true, 'active_revision' => 2, 'npc_count' => 1, 'checkpoint_count' => 1];
        $exported = ['ETag' => self::ETAG, 'Lingara-Dialogue-Revision' => '2'];
        return match (true) {
            $path === '/v1/dialogues' && $request->getMethod() === 'GET' => FakeHttpClient::json(200, ['items' => [$item], 'next_cursor' => null, 'has_more' => false]),
            str_ends_with($path, '/export') && $request->hasHeader('If-None-Match') => new Response(304, $exported),
            str_ends_with($path, '/export') => FakeHttpClient::json(200, $definition, $exported),
            str_ends_with($path, '/draft') => FakeHttpClient::json(409, ['code' => 'dialogue_conflict', 'error' => 'This dialogue changed since you opened it.']),
            default => FakeHttpClient::json($path === '/v1/dialogues' ? 201 : 200, $dialogue),
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
