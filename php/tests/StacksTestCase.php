<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Client;
use Lingara\HttpStack;
use Lingara\Model\VocabRequest;
use Lingara\Tests\Support\ScriptedServer;
use PHPUnit\Framework\TestCase;

/** Shared by the tests that run against both built stacks over a real loopback socket. */
abstract class StacksTestCase extends TestCase
{
    /** @return array<string, array{HttpStack}> */
    public static function stacks(): array
    {
        return ['symfony' => [HttpStack::symfony()], 'guzzle' => [HttpStack::guzzle()]];
    }

    /** A credential-free client on $server, so a stream needs no token exchange. */
    protected static function client(HttpStack $http, ScriptedServer $server, float $idle = 0.5, float $tokenTimeout = 30.0): Client
    {
        return new Client(
            baseUrl: $server->url,
            tokenUrl: $server->url . '/oauth/token',
            streamIdleTimeout: $idle,
            tokenRequestTimeout: $tokenTimeout,
            http: $http,
        );
    }

    protected static function vocab(): VocabRequest
    {
        return new VocabRequest(['level' => 2, 'source_lang' => 'en', 'target_lang' => 'zh']);
    }

    protected static function frame(string $event, string $data): string
    {
        return ScriptedServer::chunk("event: {$event}\ndata: {$data}\n\n");
    }

    protected static function started(): string
    {
        return self::frame('started', '{"meta":{"level":2,"source_lang":"en","target_lang":"zh","framework":"HSK","count":2,"ai_generated":true}}');
    }

    protected static function item(string $word = 'ni hao'): string
    {
        return self::frame('item', '{"word":"' . $word . '","translation":"hello"}');
    }
}
