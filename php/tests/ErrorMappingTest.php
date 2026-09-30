<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Client;
use Lingara\Exception\ApiException;
use Lingara\Exception\LingaraException;
use Lingara\Exception\MaintenanceException;
use Lingara\Exception\OAuthException;
use Lingara\Internal\ErrorMapper;
use Lingara\Tests\Support\FakeHttpClient;
use Nyholm\Psr7\Response;
use PHPUnit\Framework\TestCase;

final class ErrorMappingTest extends TestCase
{
    /**
     * 29.9.26u AC13: a plain-text 503 from either endpoint is
     * MaintenanceException; a non-envelope /v1 502 is ApiException with
     * errorCode() http_502 and getCode() 502; a non-RFC 6749 token-endpoint
     * 500 is OAuthException with error() http_500; each is a LingaraException.
     */
    public function testResponsesMapToTheErrorFamily(): void
    {
        $maintenance = static fn(): Response => new Response(503, ['Content-Type' => 'text/plain; charset=utf-8'], 'Service is under maintenance. Please try again later.');

        $v1 = self::thrown(new Client(http: (new FakeHttpClient($maintenance()))->stack()));
        self::assertInstanceOf(MaintenanceException::class, $v1);
        self::assertSame('Service is under maintenance. Please try again later.', $v1->body());

        $token = self::thrown(new Client(clientId: 'lgr_cid_x', clientSecret: 'lgr_cs_x', http: (new FakeHttpClient($maintenance()))->stack()), 'getUsage');
        self::assertInstanceOf(MaintenanceException::class, $token);

        $proxy = self::thrown(new Client(http: (new FakeHttpClient(new Response(502, ['Content-Type' => 'text/html'], '<html>bad gateway</html>')))->stack()));
        self::assertInstanceOf(ApiException::class, $proxy);
        self::assertSame(['http_502', 502, 502], [$proxy->errorCode(), $proxy->getCode(), $proxy->status()]);

        $oauth = self::thrown(new Client(clientId: 'lgr_cid_x', clientSecret: 'lgr_cs_x', http: (new FakeHttpClient(new Response(500, [], '')))->stack()), 'getUsage');
        self::assertInstanceOf(OAuthException::class, $oauth);
        self::assertSame(['http_500', null, 500], [$oauth->error(), $oauth->description(), $oauth->status()]);

        $long = str_repeat('é', 600);
        $cut = ErrorMapper::truncate($long, 1023);
        self::assertSame(1022, strlen($cut), 'a cut inside a character drops the partial byte');
        self::assertSame(1, preg_match('//u', $cut));
    }

    private static function thrown(Client $client, string $operation = 'getOpenApiDocument'): LingaraException
    {
        try {
            $operation === 'getUsage' ? $client->getUsage() : $client->getOpenApiDocument();
        } catch (LingaraException $e) {
            return $e;
        }
        self::fail('nothing was thrown');
    }
}
