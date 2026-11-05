<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Client;
use Lingara\Dialogue\ExportedDialogue\Body;
use Lingara\Dialogue\ExportedDialogue\NotModified;
use Lingara\Exception\ApiException;
use Lingara\Model\AtomKind;
use Lingara\Model\DialoguePlanRequest;
use Lingara\Model\DialoguePlanResponseOneOf;
use Lingara\Model\DialoguePlanResponseOneOf1;
use Lingara\Model\DialogueSummary;
use Lingara\Model\KeptReason;
use Lingara\Model\PlanReason;
use Lingara\Model\DialogueWarningCode;
use Lingara\Model\RenameDialogueRequest;
use Lingara\Model\SaveDialogueDraftRequest;
use Lingara\Tests\Support\FakeHttpClient;
use Nyholm\Psr7\Response;
use PHPUnit\Framework\TestCase;
use Psr\Http\Message\RequestInterface;
use Psr\Log\NullLogger;

final class DialogueTest extends TestCase
{
    private const DEFINITION = '{"schema":"dialogue.v1","id":"night_market_keepers","start":"greet","npcs":[],"checkpoints":[],"facts":[],"x-layout":{"greet":{"x":120,"y":40}}}';
    private const ETAG = '"3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e3b7e"';
    /** Not what any encoder writes: the export must hand back these bytes, not a re-encoding. */
    private const IR = '{"checkpoints":[],"facts":[],  "id":"night_market_keepers","schema":"dialogue.v1","start":"greet"}';

    /**
     * 29.10.26c AC22: through $client->dialogues(), PUT and PATCH reach the
     * fake with their JSON bodies and Content-Type application/json; the
     * export answers Body with the unparsed bytes and both headers, sends
     * revision then layout as its query, and answers NotModified on a 304
     * with If-None-Match sent unchanged; pageDialogues() ends on has_more
     * false with its filters resent. Step 0's fact rides along: a warning
     * code this library does not list decodes to the enum's unknown case,
     * never a failure.
     */
    public function testPutPatchExportAndPaging(): void
    {
        $exportHeaders = ['ETag' => self::ETAG, 'Lingara-Dialogue-Revision' => '3'];
        $fake = new FakeHttpClient(
            FakeHttpClient::token(),
            FakeHttpClient::json(200, self::dialogue()),
            FakeHttpClient::json(200, self::dialogue()),
            FakeHttpClient::json(200, self::dialogue()),
            FakeHttpClient::json(200, self::IR, $exportHeaders),
            new Response(304, $exportHeaders),
            FakeHttpClient::json(200, self::page('d-1', 'dl1', true)),
            FakeHttpClient::json(200, self::page('d-2', null, false)),
        );
        $dialogues = (new Client(clientId: 'lgr_cid_x', clientSecret: 'lgr_cs_x', logger: new NullLogger(), http: $fake->stack()))->dialogues();

        $read = $dialogues->getDialogue('keepers/night market')->value;
        self::assertSame(DialogueWarningCode::UNKNOWN_DEFAULT_OPEN_API, $read->getWarnings()[0]->getCode());
        $draft = $read->getDraft();
        self::assertNotNull($draft);
        $dialogues->saveDialogueDraft('d-1', new SaveDialogueDraftRequest(['definition' => $draft->getDefinition(), 'draft_version' => $draft->getDraftVersion()]));
        $dialogues->renameDialogue('d-1', new RenameDialogueRequest(['name' => 'Renamed']));
        $body = $dialogues->exportDialogue('d-1');
        self::assertEquals(new Body(3, self::ETAG, self::IR, null), $body);
        $same = $dialogues->exportDialogue('d-1', revision: 'draft', layout: 'omit', ifNoneMatch: self::ETAG);
        self::assertEquals(new NotModified(3, self::ETAG, null), $same);
        $ids = array_map(
            static fn(DialogueSummary $item): string => $item->getId(),
            iterator_to_array($dialogues->pageDialogues(['name' => 'market', 'limit' => 1]), false),
        );
        self::assertSame(['d-1', 'd-2'], $ids);

        [, $get, $put, $patch, $export, $revalidate, $first, $second] = $fake->requests;
        self::assertSame('/v1/dialogues/keepers%2Fnight%20market', $get->getUri()->getPath());
        self::assertSame(['PUT', '/v1/dialogues/d-1/draft'], [$put->getMethod(), $put->getUri()->getPath()]);
        self::assertEquals(json_decode('{"definition":' . self::DEFINITION . ',"draft_version":4}'), self::body($put));
        self::assertSame(['PATCH', '/v1/dialogues/d-1'], [$patch->getMethod(), $patch->getUri()->getPath()]);
        self::assertEquals(json_decode('{"name":"Renamed"}'), self::body($patch));
        foreach ([$put, $patch] as $sent) {
            self::assertSame('application/json', $sent->getHeaderLine('Content-Type'));
        }
        self::assertSame(['GET', '/v1/dialogues/d-1/export', '', false], [$export->getMethod(), $export->getUri()->getPath(),
            $export->getUri()->getQuery(), $export->hasHeader('If-None-Match')]);
        self::assertSame(['revision=draft&layout=omit', self::ETAG], [$revalidate->getUri()->getQuery(), $revalidate->getHeaderLine('If-None-Match')]);
        self::assertSame('name=market&limit=1', $first->getUri()->getQuery());
        self::assertSame('cursor=dl1&name=market&limit=1', $second->getUri()->getQuery());
        self::assertCount(8, $fake->requests);
    }

    /**
     * 3.11.26n AC8: planDialogue sends the body as given (an x- member
     * inside the document intact), answers a typed fragment and a keep, both
     * results, and a 503 with Retry-After is thrown after one request,
     * beside the turn it shares K4's exemption with.
     */
    public function testPlanDialogueIsSentOnce(): void
    {
        $planned = '{"status":"planned","fragment":{"anchor":"p1_0","beats":[{"id":"p1_0","atom":"elicit","depth":0,"sketch":false,'
            . '"angle":"ask what is in the crate","exits":[{"to":"p1_1","detour":"cargo"}]}]}}';
        $fake = new FakeHttpClient(
            FakeHttpClient::token(),
            FakeHttpClient::json(200, $planned),
            FakeHttpClient::json(200, '{"status":"kept","reason":"rejected"}'),
            FakeHttpClient::json(503, '{"code":"unavailable","error":"Later."}', ['Retry-After' => '1']),
        );
        $client = new Client(clientId: 'lgr_cid_x', clientSecret: 'lgr_cs_x', logger: new NullLogger(), http: $fake->stack());
        $document = json_decode('{"schema":"dialogue.v2","x-editor":{"zoom":2}}');
        $request = new DialoguePlanRequest(['document' => $document, 'npc' => 'ma_ren', 'reason' => PlanReason::START, 'id_prefix' => 'p1', 'turns' => 0, 'beats' => 0]);

        $planned = $client->planDialogue($request)->value;
        self::assertInstanceOf(DialoguePlanResponseOneOf::class, $planned);
        $beat = $planned->getFragment()->getBeats()[0];
        $exit = ($beat->getExits() ?? [])[0] ?? null;
        self::assertSame([AtomKind::ELICIT, 'ask what is in the crate', 'cargo'], [$beat->getAtom(), $beat->getAngle(), $exit?->getDetour()]);
        $sent = self::body($fake->requests[1]);
        self::assertInstanceOf(\stdClass::class, $sent);
        self::assertEquals($document, $sent->document);

        $kept = $client->planDialogue($request)->value;
        self::assertInstanceOf(DialoguePlanResponseOneOf1::class, $kept);
        self::assertSame(['kept', KeptReason::REJECTED], [$kept->getStatus(), $kept->getReason()]);

        try {
            $client->planDialogue($request);
            self::fail('a 503 was not thrown');
        } catch (ApiException $e) {
            self::assertSame([503, 1], [$e->status(), $e->retryAfter()]);
        }
        self::assertCount(4, $fake->requests);
    }

    private static function body(RequestInterface $request): mixed
    {
        return json_decode((string) $request->getBody(), false, 512, JSON_THROW_ON_ERROR);
    }

    private static function dialogue(): string
    {
        return '{"id":"d-1","name":"Night market","created_at":"2026-10-28T09:00:00Z","updated_at":"2026-10-28T10:30:00Z",'
            . '"draft":{"revision":3,"draft_version":4,"definition":' . self::DEFINITION . '},"active":null,'
            . '"warnings":[{"code":"a_code_from_a_later_version","path":"/checkpoints/0"}]}';
    }

    private static function page(string $id, ?string $next, bool $more): string
    {
        $item = ['id' => $id, 'name' => 'Night market', 'ir_id' => 'night_market_keepers', 'created_at' => '2026-10-28T09:00:00Z',
            'updated_at' => '2026-10-28T10:30:00Z', 'has_draft' => true, 'active_revision' => null, 'npc_count' => 1, 'checkpoint_count' => 3];
        return json_encode(['items' => [$item], 'next_cursor' => $next, 'has_more' => $more], JSON_THROW_ON_ERROR);
    }
}
