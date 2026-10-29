<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Client;
use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\Model\CanvasSummary;
use Lingara\Model\RenameCanvasRequest;
use Lingara\Model\SaveCanvasDraftRequest;
use Lingara\Tests\Support\FakeHttpClient;
use PHPUnit\Framework\TestCase;
use Psr\Http\Message\RequestInterface;
use Psr\Log\NullLogger;

final class CanvasTest extends TestCase
{
    private const DEFINITION = '{"schema":2,"nodes":[{"id":"n1","kind":"client","position":[0.5,1.5],"fields":{"name":"Lesson server","x_editor_hint":{"collapsed":true}}}]}';

    /**
     * 16.10.26ak AC15: through $client->canvases(), PUT and PATCH reach the
     * fake with their JSON bodies and Content-Type application/json; a
     * definition read then saved keeps an unknown `fields` key, sent as the
     * JSON value read; pageCanvases() ends on has_more false with its
     * filters resent, and throws malformed_response on a page with has_more
     * but no cursor; and Client.php stays at most 600 lines.
     */
    public function testPutPatchAndPaging(): void
    {
        $fake = new FakeHttpClient(
            FakeHttpClient::token(),
            FakeHttpClient::json(200, self::canvas()),
            FakeHttpClient::json(200, self::canvas()),
            FakeHttpClient::json(200, self::canvas()),
            FakeHttpClient::json(200, self::page('c-1', 'cv1', true)),
            FakeHttpClient::json(200, self::page('c-2', null, false)),
            FakeHttpClient::json(200, self::page('c-3', null, true)),
        );
        $canvases = (new Client(clientId: 'lgr_cid_x', clientSecret: 'lgr_cs_x', logger: new NullLogger(), http: $fake->stack()))->canvases();

        $draft = $canvases->getCanvas('c-1')->value->getDraft();
        self::assertNotNull($draft);
        $save = new SaveCanvasDraftRequest(['definition' => $draft->getDefinition(), 'draft_version' => $draft->getDraftVersion()]);
        $canvases->saveCanvasDraft('c-1', $save);
        $canvases->renameCanvas('c-1', new RenameCanvasRequest(['name' => 'Renamed']));
        $ids = array_map(
            static fn(CanvasSummary $item): string => $item->getId(),
            iterator_to_array($canvases->pageCanvases(['limit' => 1, 'kinds' => ['client', 'webhook']]), false),
        );
        self::assertSame(['c-1', 'c-2'], $ids);
        try {
            iterator_to_array($canvases->pageCanvases());
            self::fail('a page with has_more and no cursor did not throw');
        } catch (TransportException $e) {
            self::assertSame(TransportKind::MalformedResponse, $e->kind());
        }

        [, , $put, $patch, $first, $second] = $fake->requests;
        self::assertSame(['PUT', '/v1/canvases/c-1/draft'], [$put->getMethod(), $put->getUri()->getPath()]);
        self::assertEquals(json_decode('{"definition":' . self::DEFINITION . ',"draft_version":4}'), self::body($put));
        self::assertSame(['PATCH', '/v1/canvases/c-1'], [$patch->getMethod(), $patch->getUri()->getPath()]);
        self::assertEquals(json_decode('{"name":"Renamed"}'), self::body($patch));
        foreach ([$put, $patch] as $sent) {
            self::assertSame('application/json', $sent->getHeaderLine('Content-Type'));
        }
        self::assertSame('limit=1&kinds=client%2Cwebhook', $first->getUri()->getQuery());
        self::assertSame('cursor=cv1&limit=1&kinds=client%2Cwebhook', $second->getUri()->getQuery());
        self::assertCount(7, $fake->requests);
        self::assertLessThanOrEqual(600, count(file(__DIR__ . '/../src/Client.php') ?: []));
    }

    private static function body(RequestInterface $request): mixed
    {
        return json_decode((string) $request->getBody(), false, 512, JSON_THROW_ON_ERROR);
    }

    private static function canvas(): string
    {
        return '{"id":"c-1","name":"Hooks","created_at":"2026-10-20T09:00:00Z","updated_at":"2026-10-20T09:00:00Z",'
            . '"draft":{"revision":1,"draft_version":4,"definition":' . self::DEFINITION . ',"objects":{}},"active":null}';
    }

    private static function page(string $id, ?string $next, bool $more): string
    {
        $item = ['id' => $id, 'name' => 'Hooks', 'recipe' => 'blank', 'created_at' => '2026-10-20T09:00:00Z', 'updated_at' => '2026-10-20T09:00:00Z',
            'has_draft' => true, 'active_revision' => null, 'node_count' => 1, 'node_kinds' => ['client'], 'scopes' => [], 'objects' => []];
        return json_encode(['items' => [$item], 'total' => null, 'next_cursor' => $next, 'has_more' => $more], JSON_THROW_ON_ERROR);
    }
}
