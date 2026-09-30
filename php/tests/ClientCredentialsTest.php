<?php

declare(strict_types=1);

namespace Lingara\Tests;

use Lingara\AccessToken;
use Lingara\AuthMethod;
use Lingara\ClientCredentials;
use Lingara\Exception\OAuthException;
use Lingara\Tests\Support\ArrayCache;
use Lingara\Tests\Support\FakeClock;
use Lingara\Tests\Support\FakeHttpClient;
use Psr\Log\NullLogger;
use PHPUnit\Framework\TestCase;

final class ClientCredentialsTest extends TestCase
{
    /**
     * 29.9.26u AC8: with expires_in 3600 a token is reused at 3539 s and
     * replaced at 3541 s after send, and with expires_in 40 it is stale at
     * 20 s.
     */
    public function testRefreshesAtMinOfSixtySecondsAndHalfTheLifetime(): void
    {
        [$source, $fake, $clock] = self::source(null, FakeHttpClient::token('lgr_at_one'), FakeHttpClient::token('lgr_at_two'));
        $seen = [self::raw($source)];
        $clock->advance(3539);
        $seen[] = self::raw($source);
        $clock->advance(2);
        $seen[] = self::raw($source);
        self::assertSame(['lgr_at_one', 'lgr_at_one', 'lgr_at_two'], $seen, 'reused at 3539 s, replaced at 3541 s');
        self::assertCount(2, $fake->requests);

        [$source, $fake, $clock] = self::source(null, FakeHttpClient::token('lgr_at_short', 40), FakeHttpClient::token('lgr_at_next', 40));
        $source->token();
        $clock->advance(19.9);
        self::assertSame('lgr_at_short', self::raw($source));
        $clock->advance(0.1);
        self::assertSame('lgr_at_next', self::raw($source));
    }

    /**
     * 29.9.26u AC9: invalidate of an older token leaves a newer token in
     * place, in memory and in the PSR-16 entry.
     */
    public function testInvalidateIsCompareAndClearInMemoryAndPsr16(): void
    {
        $cache = new ArrayCache();
        [$source, $fake] = self::source($cache, FakeHttpClient::token('lgr_at_old'), FakeHttpClient::token('lgr_at_new'));
        $old = $source->token();
        $source->invalidate($old);
        $new = $source->token();
        self::assertSame('lgr_at_new', $new->exposeSecret());

        $source->invalidate($old);
        self::assertSame('lgr_at_new', self::raw($source));
        self::assertSame(['t' => 'lgr_at_new', 's' => 1_790_000_000.0 + 3540], $cache->values[$source->cacheKey()]);
        self::assertCount(2, $fake->requests);

        $source->invalidate(new AccessToken('lgr_at_new'));
        self::assertArrayNotHasKey($source->cacheKey(), $cache->values);
    }

    /**
     * 29.9.26u AC10: a second ClientCredentials sharing a PSR-16 cache reuses
     * the first one's token with no exchange; the key is at most 64
     * characters from PSR-16's allowed set and the value is a plain array;
     * the TTL ends at the stale point; a cache that throws, or holds a value
     * of the wrong shape, is a miss and the call succeeds; and a failed
     * exchange caches nothing.
     */
    public function testPsr16CacheSharesATokenAcrossInstances(): void
    {
        $cache = new ArrayCache();
        [$first] = self::source($cache, FakeHttpClient::token('lgr_at_shared'));
        $first->token();
        [$second, $secondFake] = self::source($cache);
        self::assertSame('lgr_at_shared', self::raw($second));
        self::assertCount(0, $secondFake->requests);

        $key = $first->cacheKey();
        self::assertLessThanOrEqual(64, strlen($key));
        self::assertMatchesRegularExpression('/\A[A-Za-z0-9_.]+\z/', $key);
        self::assertSame(['t' => 'lgr_at_shared', 's' => 1_790_000_000.0 + 3540], $cache->values[$key]);
        self::assertSame(3540, $cache->ttls[$key]);

        $cache->broken = true;
        [$third] = self::source($cache, FakeHttpClient::token('lgr_at_own'));
        self::assertSame('lgr_at_own', self::raw($third));

        $cache->broken = false;
        $cache->values[$key] = 'not an entry';
        [$fourth] = self::source($cache, FakeHttpClient::token('lgr_at_fresh'));
        self::assertSame('lgr_at_fresh', self::raw($fourth));

        $empty = new ArrayCache();
        [$failing, $fake] = self::source($empty, FakeHttpClient::json(401, ['error' => 'invalid_client']), FakeHttpClient::token('lgr_at_later'));
        try {
            $failing->token();
            self::fail('a refused exchange did not throw');
        } catch (OAuthException $e) {
            self::assertSame('invalid_client', $e->error());
        }
        self::assertSame([], $empty->values);
        self::assertSame('lgr_at_later', self::raw($failing));
        self::assertCount(2, $fake->requests);
    }

    /**
     * 29.9.26u AC11: a secret holding `+`, `/`, `%` and a space is
     * form-encoded in each Basic half, and AuthMethod::Post sends it in the
     * body and never both.
     */
    public function testClientAuthenticationEncodesEachHalf(): void
    {
        $secret = 'lgr_cs_a+b/c%d e';
        [$basic, $fake] = self::build(null, $secret, AuthMethod::Basic, FakeHttpClient::token());
        $basic->token();
        $request = $fake->requests[0];
        self::assertSame('Basic ' . base64_encode('lgr_cid_x:lgr_cs_a%2Bb%2Fc%25d+e'), $request->getHeaderLine('Authorization'));
        self::assertSame('grant_type=client_credentials', (string) $request->getBody());

        [$post, $fake] = self::build(null, $secret, AuthMethod::Post, FakeHttpClient::token());
        $post->token();
        $request = $fake->requests[0];
        self::assertFalse($request->hasHeader('Authorization'));
        parse_str((string) $request->getBody(), $form);
        self::assertSame(['grant_type' => 'client_credentials', 'client_id' => 'lgr_cid_x', 'client_secret' => $secret], $form);
    }

    /**
     * The token the source hands out now: a fresh call each time, never a
     * remembered value.
     *
     * @phpstan-impure
     */
    private static function raw(ClientCredentials $source): string
    {
        return $source->token()->exposeSecret();
    }

    /** @return array{ClientCredentials, FakeHttpClient, FakeClock} */
    private static function source(
        ?ArrayCache $cache,
        \Psr\Http\Message\ResponseInterface ...$responses,
    ): array {
        return self::build($cache, 'lgr_cs_x', AuthMethod::Basic, ...$responses);
    }

    /** @return array{ClientCredentials, FakeHttpClient, FakeClock} */
    private static function build(
        ?ArrayCache $cache,
        string $secret,
        AuthMethod $method,
        \Psr\Http\Message\ResponseInterface ...$responses,
    ): array {
        $fake = new FakeHttpClient(...$responses);
        $clock = new FakeClock();
        $source = new ClientCredentials(
            'lgr_cid_x',
            $secret,
            authMethod: $method,
            tokenCache: $cache,
            http: $fake->stack(),
            clock: $clock,
            sleeper: $clock->sleep(...),
            logger: new NullLogger(),
        );
        return [$source, $fake, $clock];
    }
}
