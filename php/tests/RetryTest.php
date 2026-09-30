<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\Client;
use Lingara\Exception\ApiException;
use Lingara\Tests\Support\FakeClock;
use Lingara\Tests\Support\FakeHttpClient;
use PHPUnit\Framework\TestCase;

final class RetryTest extends TestCase
{
    /**
     * 29.9.26u AC15: a Retry-After above retryAfterCap throws at once with
     * retryAfter() set; a missing one throws at once without calling the
     * sleeper; an HTTP-date is read against the clock; three 429s throw
     * after two sleeps.
     */
    public function testRetryAfterCapMissingHeaderDateAndExhaustion(): void
    {
        $limited = self::limited(...);

        [$client, $fake, $clock] = self::client($limited(['Retry-After' => '61']));
        self::assertSame(61, self::refusal($client)->retryAfter());
        self::assertSame([], $clock->sleeps);
        self::assertCount(1, $fake->requests);

        [$client, $fake, $clock] = self::client($limited());
        self::assertNull(self::refusal($client)->retryAfter());
        self::assertSame([], $clock->sleeps);

        [$client, $fake, $clock] = self::client(
            $limited(['Retry-After' => gmdate('D, d M Y H:i:s \G\M\T', 1_790_000_007)]),
            FakeHttpClient::json(200, ['allowance' => []]),
        );
        $client->getUsage();
        self::assertSame([7.0], $clock->sleeps);

        [$client, $fake, $clock] = self::client(
            $limited(['Retry-After' => '2']),
            $limited(['Retry-After' => '3']),
            $limited(['Retry-After' => '4']),
        );
        self::assertSame(4, self::refusal($client)->retryAfter());
        self::assertSame([2.0, 3.0], $clock->sleeps);
        self::assertCount(3, $fake->requests);
    }

    /** @param array<string, string> $headers */
    private static function limited(array $headers = []): \Psr\Http\Message\ResponseInterface
    {
        return FakeHttpClient::json(429, ['code' => 'rate_limited', 'error' => 'slow down'], $headers);
    }

    /** @return array{Client, FakeHttpClient, FakeClock} */
    private static function client(\Psr\Http\Message\ResponseInterface ...$responses): array
    {
        $fake = new FakeHttpClient(...$responses);
        $clock = new FakeClock();
        $client = new Client(clock: $clock, sleeper: $clock->sleep(...), http: $fake->stack());
        return [$client, $fake, $clock];
    }

    private static function refusal(Client $client): ApiException
    {
        try {
            $client->getOpenApiDocument();
        } catch (ApiException $e) {
            self::assertSame(429, $e->status());
            return $e;
        }
        self::fail('no ApiException');
    }
}
