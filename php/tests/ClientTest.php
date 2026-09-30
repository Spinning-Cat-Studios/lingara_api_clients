<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\AccessToken;
use Lingara\AuthMethod;
use Lingara\Client;
use Lingara\EventStream;
use Lingara\Exception\ApiException;
use Lingara\Internal\Operations;
use Lingara\Model\LessonPlan;
use Lingara\Model\Usage;
use Lingara\Model\VersionDetail;
use Lingara\Model\VersionList;
use Lingara\Tests\Support\ArrayCache;
use Lingara\Tests\Support\FakeHttpClient;
use Lingara\TokenSource;
use PHPUnit\Framework\TestCase;
use Psr\Log\NullLogger;

final class ClientTest extends TestCase
{
    private const VERSION = '2026-09-knowing-tenpounder';

    /**
     * 29.9.26u AC22: the nine client methods and Operations' keys name the
     * same operations, both ways, and the three path-parameter methods take
     * one string; each JSON method's model is its operation's `200` schema.
     */
    public function testOperationMethodsMatchGeneratedOperations(): void
    {
        $methods = [];
        foreach ((new \ReflectionClass(Client::class))->getMethods(\ReflectionMethod::IS_PUBLIC) as $method) {
            $type = $method->getReturnType();
            if ($type instanceof \ReflectionNamedType && in_array($type->getName(), [EventStream::class, \Lingara\ApiResponse::class], true)) {
                $methods[$method->getName()] = $method;
            }
        }
        $operations = array_keys(Operations::OPERATIONS);
        $names = array_keys($methods);
        sort($names);
        self::assertSame($operations, $names);
        foreach (Operations::OPERATIONS as $id => $operation) {
            $params = $methods[$id]->getParameters();
            if ($operation['pathParams'] !== []) {
                self::assertCount(1, $params, $id);
                self::assertSame('string', (string) $params[0]->getType(), $id);
            }
        }
        self::assertSame([
            'getApiVersion' => VersionDetail::class, 'getLessonPlan' => LessonPlan::class, 'getOpenApiDocument' => null,
            'getUsage' => Usage::class, 'listApiVersions' => VersionList::class,
        ], array_map(
            static fn(array $o): ?string => $o['response'],
            array_filter(Operations::OPERATIONS, static fn(array $o): bool => $o['stream'] === null),
        ));
    }

    /**
     * 29.9.26u AC23: a pinned client sends Lingara-Version on every /v1
     * request and never to the token endpoint; ApiResponse carries
     * servedVersion from the echo; a credential-free client calls the three
     * public operations with no exchange and no Authorization header.
     */
    public function testHeadersServedVersionAndTheCredentialFreeClient(): void
    {
        $echo = ['Lingara-Version' => self::VERSION];
        $fake = new FakeHttpClient(
            FakeHttpClient::token(),
            FakeHttpClient::json(200, ['allowance' => []], $echo),
            FakeHttpClient::json(200, ['openapi' => '3.2.0'], $echo),
        );
        $client = new Client(clientId: 'lgr_cid_x', clientSecret: 'lgr_cs_x', version: self::VERSION, logger: new NullLogger(), http: $fake->stack());
        $usage = $client->getUsage();
        $client->getOpenApiDocument();
        self::assertSame(self::VERSION, $usage->servedVersion);
        self::assertInstanceOf(Usage::class, $usage->value);
        [$token, $first, $second] = $fake->requests;
        self::assertFalse($token->hasHeader('Lingara-Version'));
        self::assertSame(self::VERSION, $first->getHeaderLine('Lingara-Version'));
        self::assertSame(self::VERSION, $second->getHeaderLine('Lingara-Version'));
        self::assertSame('Bearer lgr_at_fake', $first->getHeaderLine('Authorization'));

        $public = new FakeHttpClient(
            FakeHttpClient::json(200, ['openapi' => '3.2.0']),
            FakeHttpClient::json(200, ['current' => self::VERSION, 'development' => self::VERSION, 'versions' => []]),
            FakeHttpClient::json(200, ['id' => self::VERSION, 'state' => 'supported', 'lts' => false, 'minted_at' => '2026-09-20T09:00:00Z',
                'summary' => 'x', 'history' => [], 'spec' => ['url' => '/v1/openapi.json', 'sha256' => str_repeat('0', 64)]]),
        );
        $anonymous = new Client(http: $public->stack());
        self::assertNull($anonymous->tokenSource);
        self::assertSame('3.2.0', $anonymous->getOpenApiDocument()->value->openapi);
        self::assertSame(self::VERSION, $anonymous->listApiVersions()->value->getCurrent());
        self::assertSame(self::VERSION, $anonymous->getApiVersion(self::VERSION)->value->getId());
        self::assertSame('/v1/versions/' . self::VERSION, $public->requests[2]->getUri()->getPath());
        foreach ($public->requests as $request) {
            self::assertFalse($request->hasHeader('Authorization'));
            self::assertFalse($request->hasHeader('Lingara-Version'), 'an unpinned client sends no version');
        }

        $refused = new FakeHttpClient(FakeHttpClient::json(401, ['code' => 'unauthorized', 'error' => 'no token']));
        try {
            (new Client(http: $refused->stack()))->getUsage();
            self::fail('a 401 did not throw');
        } catch (ApiException $e) {
            self::assertSame(401, $e->status());
        }
        self::assertCount(1, $refused->requests, 'no 401 retry without a token source');
    }

    /**
     * 29.9.26u AC24: new Client throws \InvalidArgumentException for an empty
     * version, for tokenSource beside each of clientId, clientSecret,
     * authMethod, scopes and tokenCache, and for clientId without
     * clientSecret and the reverse.
     */
    public function testConstructionRefusesConflictingOptions(): void
    {
        $source = new class implements TokenSource {
            public function token(): AccessToken
            {
                return new AccessToken('lgr_at_own');
            }

            public function invalidate(AccessToken $token): void {}
        };
        $http = (new FakeHttpClient())->stack();
        $refused = [
            'empty version' => static fn(): Client => new Client(version: '', http: $http),
            'clientId' => static fn(): Client => new Client(tokenSource: $source, clientId: 'lgr_cid_x', http: $http),
            'clientSecret' => static fn(): Client => new Client(tokenSource: $source, clientSecret: 'lgr_cs_x', http: $http),
            'authMethod' => static fn(): Client => new Client(tokenSource: $source, authMethod: AuthMethod::Post, http: $http),
            'scopes' => static fn(): Client => new Client(tokenSource: $source, scopes: ['usage:read'], http: $http),
            'tokenCache' => static fn(): Client => new Client(tokenSource: $source, tokenCache: new ArrayCache(), http: $http),
            'clientId alone' => static fn(): Client => new Client(clientId: 'lgr_cid_x', http: $http),
            'clientSecret alone' => static fn(): Client => new Client(clientSecret: 'lgr_cs_x', http: $http),
        ];
        foreach ($refused as $case => $build) {
            try {
                $build();
                self::fail("{$case} was accepted");
            } catch (\InvalidArgumentException) {
            }
        }
        self::assertSame($source, (new Client(tokenSource: $source, http: $http))->tokenSource);
    }
}
