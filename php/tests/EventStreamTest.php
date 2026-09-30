<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Client;
use Lingara\Exception\ApiException;
use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\HttpStack;
use Lingara\Internal\Operations;
use Lingara\Model\LessonPlanCreateRequest;
use Lingara\Model\TutorTurnRequest;
use Lingara\Stream\CreateLessonPlanEvent;
use Lingara\Stream\GenerateVocabularyEvent;
use Lingara\Stream\SendTutorMessageEvent;
use Lingara\Stream\StreamLessonPlanEvent;
use Lingara\Tests\Support\FakeHttpClient;
use Lingara\Tests\Support\ScriptedServer;
use PHPUnit\Framework\Attributes\DataProvider;
use Psr\Log\NullLogger;

final class EventStreamTest extends StacksTestCase
{
    private const PLAN = '3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37';

    /**
     * 29.9.26u AC16: on both built stacks, with streamIdleTimeout at 0.5 s, a
     * body silent for 1 s fails with Timeout; a keepalive every 50 ms keeps it
     * open; a consumer that holds one event for 1 s then reads the rest with
     * no error; and a JSON call whose headers stall past the bound is Timeout.
     */
    #[DataProvider('stacks')]
    public function testIdleTimeoutOnBothBuiltStacks(HttpStack $http): void
    {
        $keepalives = [];
        for ($i = 0; $i < 20; $i++) {
            $keepalives[] = ScriptedServer::chunk(": keepalive\n\n");
            $keepalives[] = ['sleep' => 0.05];
        }
        $server = new ScriptedServer([
            ['write' => [ScriptedServer::sseHead(), self::started(), ['sleep' => 1.0], self::item()], 'then' => 'close'],
            ['write' => [ScriptedServer::sseHead(), self::started(), ...$keepalives, self::item(), self::frame('done', '{}'), ScriptedServer::end()], 'then' => 'close'],
            ['write' => [ScriptedServer::sseHead(), self::started(), self::item('a'), self::item('b'), self::frame('done', '{}'), ScriptedServer::end()], 'then' => 'close'],
            ['write' => [['sleep' => 1.0]], 'then' => 'close'],
        ]);
        $client = self::client($http, $server);

        $seen = [];
        try {
            foreach ($client->generateVocabulary(self::vocab()) as $event) {
                $seen[] = $event;
            }
            self::fail('a silent body did not time out');
        } catch (TransportException $e) {
            self::assertSame(TransportKind::Timeout, $e->kind());
            self::assertCount(1, $seen);
        }

        $events = iterator_to_array($client->generateVocabulary(self::vocab()), false);
        self::assertCount(2, $events, 'keepalives every 50 ms keep a 0.5 s stream open');

        $words = [];
        foreach ($client->generateVocabulary(self::vocab()) as $event) {
            if ($event instanceof GenerateVocabularyEvent\Started) {
                usleep(1_000_000);
            }
            if ($event instanceof GenerateVocabularyEvent\Item) {
                $words[] = $event->data->getWord();
            }
        }
        self::assertSame(['a', 'b'], $words, 'time the consumer holds an event never counts as silence');

        try {
            $client->getOpenApiDocument();
            self::fail('a stalled JSON call did not time out');
        } catch (TransportException $e) {
            self::assertSame(TransportKind::Timeout, $e->kind());
        }
        $server->stop();
    }

    /**
     * 29.9.26u AC20: on both built stacks, `break` after the first event
     * closes the connection, which the server sees within 2 s; a second
     * getIterator() throws \LogicException; and a stream never iterated is
     * closed when its last reference goes.
     */
    #[DataProvider('stacks')]
    public function testBreakClosesTheConnectionAndTheStreamIsSingleUse(HttpStack $http): void
    {
        $held = ['write' => [ScriptedServer::sseHead(), self::started(), self::item()], 'then' => 'hold'];
        $server = new ScriptedServer([$held, $held]);
        $client = self::client($http, $server, 30.0);

        $stream = $client->generateVocabulary(self::vocab());
        foreach ($stream as $event) {
            break;
        }
        $closed = $server->next('closed ', 3.0);
        self::assertNotNull($closed, 'the server never saw the connection close');
        self::assertLessThan(2000, (int) $closed);
        try {
            $stream->getIterator();
            self::fail('a second getIterator() did not throw');
        } catch (\LogicException) {
        }

        $unused = $client->generateVocabulary(self::vocab());
        unset($unused);
        $closed = $server->next('closed ', 3.0);
        self::assertNotNull($closed, 'a dropped stream kept its connection');
        self::assertLessThan(2000, (int) $closed);
        $server->stop();
    }

    /**
     * 29.9.26u AC17: each stream operation ends on its own terminal (`result`
     * and `pending` yielded, `done` not); the terminal table in Operations
     * matches the view's endsOn, and every terminal is among its operation's
     * event names.
     */
    public function testEachOperationEndsOnItsOwnTerminal(): void
    {
        $view = json_decode((string) file_get_contents(__DIR__ . '/../../spec/generator/openapi.3.0.json'));
        self::assertInstanceOf(\stdClass::class, $view);
        $streams = [];
        foreach (Operations::OPERATIONS as $id => $operation) {
            if ($operation['stream'] !== null) {
                $streams[$id] = array_keys(array_filter($operation['stream']['events'], static fn(array $e): bool => $e['end'] !== null));
            }
        }
        $fromView = [];
        $entries = $view->{'x-lingara-streams'};
        self::assertIsArray($entries);
        foreach ($entries as $entry) {
            self::assertInstanceOf(\stdClass::class, $entry);
            self::assertIsString($entry->operationId);
            $fromView[$entry->operationId] = $entry->endsOn;
        }
        ksort($fromView);
        self::assertSame($fromView, $streams);

        $after = self::frame('item', '{"word":"never","translation":"read"}');
        $cases = [
            ['generateVocabulary', "event: started\ndata: {\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\",\"framework\":\"HSK\",\"count\":1,\"ai_generated\":true}}\n\nevent: done\ndata: {}\n\n{$after}", [GenerateVocabularyEvent\Started::class]],
            ['createLessonPlan', "event: result\ndata: {\"plan\":" . self::lessonPlan() . "}\n\n{$after}", [CreateLessonPlanEvent\Result::class]],
            ['streamLessonPlan', "event: pending\ndata: {\"plan_id\":\"" . self::PLAN . "\",\"status\":\"generating\"}\n\n{$after}", [StreamLessonPlanEvent\Pending::class]],
            ['sendTutorMessage', "event: delta\ndata: {\"text\":\"hi\"}\n\nevent: done\ndata: {}\n\n{$after}", [SendTutorMessageEvent\Delta::class]],
        ];
        foreach ($cases as [$operation, $body, $classes]) {
            $fake = new FakeHttpClient(FakeHttpClient::sse($body));
            $events = iterator_to_array(self::call(new Client(http: $fake->stack()), $operation), false);
            self::assertSame($classes, array_map(static fn(object $e): string => $e::class, $events), $operation);
        }
    }

    /**
     * 29.9.26u AC18: an `error` event throws ApiException from inside the
     * foreach with status 200, errorCode, message, planId and servedVersion;
     * it is never yielded and never retried.
     */
    public function testErrorEventThrowsApiExceptionWithPlanId(): void
    {
        $body = "event: started\ndata: {\"plan_id\":\"" . self::PLAN . "\"}\n\n"
            . "event: error\ndata: {\"code\":\"upstream_unavailable\",\"message\":\"the model is away\",\"plan_id\":\"" . self::PLAN . "\"}\n\n";
        $fake = new FakeHttpClient(FakeHttpClient::sse($body, ['Lingara-Version' => '2026-09-knowing-tenpounder']));
        $client = new Client(logger: new NullLogger(), http: $fake->stack());
        $seen = [];
        try {
            foreach (self::call($client, 'createLessonPlan') as $event) {
                $seen[] = $event;
            }
            self::fail('the error event did not throw');
        } catch (ApiException $e) {
            self::assertSame([200, 'upstream_unavailable', 'the model is away', self::PLAN, '2026-09-knowing-tenpounder'], [
                $e->status(), $e->errorCode(), $e->getMessage(), $e->planId(), $e->servedVersion(),
            ]);
        }
        self::assertCount(1, $seen);
        self::assertCount(1, $fake->requests, 'an error event is never retried');
    }

    /**
     * 29.9.26u AC19: a non-SSE 200 is MalformedResponse from the call, an
     * unknown event is skipped, a known event whose data is a JSON array is
     * MalformedEvent, EOF before a terminal is StreamEndedEarly, and bytes
     * after a terminal are never read.
     */
    public function testTheStreamEndsPerC2D6(): void
    {
        $client = static fn(FakeHttpClient $fake): Client => new Client(http: $fake->stack());
        try {
            self::call($client(new FakeHttpClient(FakeHttpClient::json(200, ['not' => 'sse']))), 'generateVocabulary');
            self::fail('a JSON 200 on a stream did not throw');
        } catch (TransportException $e) {
            self::assertSame(TransportKind::MalformedResponse, $e->kind());
        }

        $unknown = "event: surprise\ndata: {\"x\":1}\n\nevent: delta\ndata: {\"text\":\"hi\"}\n\nevent: done\ndata: {}\n\n";
        self::assertCount(1, iterator_to_array(self::call($client(new FakeHttpClient(FakeHttpClient::sse($unknown))), 'sendTutorMessage'), false));

        self::assertKind(TransportKind::MalformedEvent, $client(new FakeHttpClient(FakeHttpClient::sse("event: delta\ndata: [1,2]\n\n"))));
        self::assertKind(TransportKind::StreamEndedEarly, $client(new FakeHttpClient(FakeHttpClient::sse("event: delta\ndata: {\"text\":\"hi\"}\n\n"))));

        $after = "event: done\ndata: {}\n\nevent: delta\ndata: [not json";
        self::assertSame([], iterator_to_array(self::call($client(new FakeHttpClient(FakeHttpClient::sse($after))), 'sendTutorMessage'), false));
    }

    private static function assertKind(TransportKind $kind, Client $client): void
    {
        try {
            iterator_to_array(self::call($client, 'sendTutorMessage'), false);
            self::fail("no {$kind->value}");
        } catch (TransportException $e) {
            self::assertSame($kind, $e->kind());
        }
    }

    /** @return iterable<object> */
    private static function call(Client $client, string $operation): iterable
    {
        return match ($operation) {
            'generateVocabulary' => $client->generateVocabulary(self::vocab()),
            'createLessonPlan' => $client->createLessonPlan(new LessonPlanCreateRequest(['context' => 'a night market', 'source_lang' => 'en', 'target_lang' => 'zh', 'level' => 2])),
            'streamLessonPlan' => $client->streamLessonPlan(self::PLAN),
            default => $client->sendTutorMessage(new TutorTurnRequest(['message' => 'hello', 'source_lang' => 'en', 'target_lang' => 'zh'])),
        };
    }

    private static function lessonPlan(): string
    {
        return '{"id":"' . self::PLAN . '","status":"complete","source_lang":"en","target_lang":"zh","level":2,'
            . '"created_at":"2026-09-29T10:00:00Z","ai_generated":true}';
    }
}
