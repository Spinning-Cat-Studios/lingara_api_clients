<?php

declare(strict_types=1);

namespace Lingara;

use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\Internal\ErrorLogLogger;
use Lingara\Internal\ErrorMapper;
use Lingara\Internal\Json;
use Lingara\Internal\Retry;
use Lingara\Internal\Secrets;
use Lingara\Internal\SystemClock;
use Lingara\Internal\Transport;
use Lingara\Internal\UserAgent;
use Psr\Clock\ClockInterface;
use Psr\Http\Message\RequestInterface;
use Psr\Http\Message\ResponseInterface;
use Psr\Log\LoggerInterface;
use Psr\SimpleCache\CacheInterface;

/**
 * K1: the OAuth 2.0 client-credentials token source (CONTRACT.md K1; D5).
 *
 * It caches one token and replaces it min(60 s, expires_in / 2) before it
 * expires, and clears only the token a 401 was answered with. With a PSR-16
 * `tokenCache`, the token is shared by every process using that cache, so a
 * PHP-FPM deployment mints one token per hour rather than one per web
 * request. The cache is an optimisation, never a failure: a backend that
 * throws, or an entry of the wrong shape, is a miss, logged at debug.
 *
 * PSR-18 is synchronous, so the library has no in-process concurrency and
 * single flight holds by construction. Across processes it is best-effort:
 * two workers may both mint at a cold start.
 */
final class ClientCredentials implements TokenSource
{
    private const CACHE_PREFIX = 'lgr_tok.v1.';

    /** Keys the secret, the PSR-16 cache and the Transport in Secrets. */
    private readonly object $handle;
    private readonly Retry $retry;
    private readonly string $userAgent;
    private readonly LoggerInterface $logger;
    private ?AccessToken $cached = null;
    private float $staleAt = 0.0;

    /**
     * @param list<string>               $scopes  none: no `scope` parameter
     * @param (callable(float): void)|null $sleeper a testing seam: every Retry-After wait
     */
    public function __construct(
        public readonly string $clientId,
        #[\SensitiveParameter]
        string $clientSecret,
        public readonly AuthMethod $authMethod = AuthMethod::Basic,
        public readonly array $scopes = [],
        ?CacheInterface $tokenCache = null,
        public readonly string $tokenUrl = Client::DEFAULT_TOKEN_URL,
        ?HttpStack $http = null,
        int $maxAttempts = 3,
        float $retryAfterCap = 60.0,
        public readonly float $tokenRequestTimeout = 30.0,
        ?string $userAgentSuffix = null,
        ?ClockInterface $clock = null,
        ?callable $sleeper = null,
        ?LoggerInterface $logger = null,
    ) {
        if ($clientId === '' || $clientSecret === '') {
            throw new \InvalidArgumentException('clientId and clientSecret must not be empty');
        }
        if ($tokenRequestTimeout <= 0) {
            throw new \InvalidArgumentException('tokenRequestTimeout must be positive');
        }
        $this->handle = Secrets::handle();
        Secrets::put($this->handle, 'secret', $clientSecret);
        Secrets::put($this->handle, 'cache', $tokenCache);
        // An HTTP client may keep its last request, Authorization included.
        Secrets::put($this->handle, 'transport', ($http ?? HttpStack::detect())->transport($tokenRequestTimeout, $tokenRequestTimeout));
        $this->retry = new Retry($maxAttempts, $retryAfterCap, $clock ?? new SystemClock(), $sleeper ?? Client::defaultSleeper(...));
        $this->userAgent = UserAgent::build($userAgentSuffix);
        $this->logger = $logger ?? new ErrorLogLogger();
    }

    /** The raw client secret: the one accessor that does not redact. */
    public function exposeSecret(): string
    {
        return Secrets::string($this->handle, 'secret');
    }

    /** The cached token while it is fresh; otherwise one from the PSR-16 cache, or a new exchange. */
    public function token(): AccessToken
    {
        $now = $this->now();
        if ($this->cached !== null && $now < $this->staleAt) {
            return $this->cached;
        }
        $this->cached = null;
        $shared = $this->readCache($now);
        if ($shared !== null) {
            [$this->cached, $this->staleAt] = $shared;
            return $shared[0];
        }
        [$token, $staleAt] = $this->exchange();
        [$this->cached, $this->staleAt] = [$token, $staleAt];
        $this->writeCache($token, $staleAt);
        return $token;
    }

    /** Forgets $token only if it is still the cached one, in memory and in the PSR-16 entry. */
    public function invalidate(AccessToken $token): void
    {
        if ($this->cached !== null && $this->cached->equals($token)) {
            $this->cached = null;
        }
        $cache = $this->cache();
        if ($cache === null) {
            return;
        }
        // PSR-16 has no compare-and-delete: a race across workers costs one
        // extra mint, never a wrong token.
        try {
            $entry = $cache->get($this->cacheKey());
            if (is_array($entry) && ($entry['t'] ?? null) === $token->exposeSecret()) {
                $cache->delete($this->cacheKey());
            }
        } catch (\Throwable $e) {
            $this->logger->debug('the Lingara token cache failed on invalidate: ' . $e::class);
        }
    }

    /** The PSR-16 key: `lgr_tok.v1.` and 44 hex digits of client id, token URL and sorted scopes. */
    public function cacheKey(): string
    {
        $scopes = $this->scopes;
        sort($scopes);
        $digest = hash('sha256', $this->clientId . "\n" . $this->tokenUrl . "\n" . implode(' ', $scopes));
        return self::CACHE_PREFIX . substr($digest, 0, 44);
    }

    /** @return array<string, string> */
    public function __debugInfo(): array
    {
        return ['clientId' => $this->clientId, 'clientSecret' => '[REDACTED]', 'tokenUrl' => $this->tokenUrl];
    }

    /** @return array<string, mixed> */
    public function __serialize(): array
    {
        throw new \LogicException('a token source cannot be serialized');
    }

    /** @param array<mixed> $data */
    public function __unserialize(array $data): void
    {
        throw new \LogicException('a token source cannot be unserialized');
    }

    /** @return array{AccessToken, float} */
    private function exchange(): array
    {
        $sentAt = 0.0;
        $started = 0;
        // obtained_at is when the request that succeeded was sent.
        $response = $this->retry->run(function () use (&$sentAt, &$started): ResponseInterface {
            $sentAt = $this->now();
            $started = Transport::now();
            return $this->transport()->send($this->transport()->tokenClient(), $this->request(), $this->tokenRequestTimeout, $this->secrets());
        });
        $deadline = $started + (int) ($this->tokenRequestTimeout * 1e9);
        $body = $this->transport()->readAll($response->getBody(), $this->tokenRequestTimeout, $this->secrets(), $deadline);
        $status = $response->getStatusCode();
        if ($status < 200 || $status > 299) {
            throw ErrorMapper::refusal('token', $response, $body, $this->retry->now());
        }
        [$token, $lifetime] = $this->grant($body);
        return [$token, $sentAt + $lifetime - min(60.0, $lifetime / 2)];
    }

    private function request(): RequestInterface
    {
        $form = ['grant_type' => 'client_credentials'];
        if ($this->scopes !== []) {
            $form['scope'] = implode(' ', $this->scopes);
        }
        if ($this->authMethod === AuthMethod::Post) {
            $form += ['client_id' => $this->clientId, 'client_secret' => $this->exposeSecret()];
        }
        $request = $this->transport()->request('POST', $this->tokenUrl)
            ->withHeader('Content-Type', 'application/x-www-form-urlencoded')
            ->withHeader('Accept', 'application/json')
            ->withHeader('User-Agent', $this->userAgent)
            ->withBody($this->transport()->body(http_build_query($form, '', '&', PHP_QUERY_RFC1738)));
        return $this->authMethod === AuthMethod::Basic ? $request->withHeader('Authorization', $this->basic()) : $request;
    }

    /** Basic base64(form(id) ":" form(secret)), each half form-encoded per RFC 6749 §2.3.1. */
    private function basic(): string
    {
        return 'Basic ' . base64_encode(urlencode($this->clientId) . ':' . urlencode($this->exposeSecret()));
    }

    /** @return list<string> every spelling of the secret a failure might echo */
    private function secrets(): array
    {
        return [$this->exposeSecret(), urlencode($this->exposeSecret()), $this->basic()];
    }

    /** @return array{AccessToken, float} a 200's token and lifetime in seconds */
    private function grant(string $body): array
    {
        try {
            $fields = Json::decode($body);
        } catch (\JsonException) {
            throw new TransportException(TransportKind::MalformedResponse, 'the token response is not JSON');
        }
        $fields = $fields instanceof \stdClass ? get_object_vars($fields) : [];
        $raw = $fields['access_token'] ?? null;
        $lifetime = $fields['expires_in'] ?? null;
        $bearer = strcasecmp((string) self::text($fields['token_type'] ?? null), 'bearer') === 0;
        if (!is_string($raw) || $raw === '' || !(is_int($lifetime) || is_float($lifetime)) || $lifetime < 0 || !$bearer) {
            throw new TransportException(
                TransportKind::MalformedResponse,
                'the token response lacks access_token, expires_in or a Bearer token_type',
            );
        }
        return [new AccessToken($raw), (float) $lifetime];
    }

    private static function text(mixed $value): ?string
    {
        return is_string($value) ? $value : null;
    }

    /** @return array{AccessToken, float}|null */
    private function readCache(float $now): ?array
    {
        $cache = $this->cache();
        if ($cache === null) {
            return null;
        }
        try {
            $entry = $cache->get($this->cacheKey());
        } catch (\Throwable $e) {
            $this->logger->debug('the Lingara token cache failed on read: ' . $e::class);
            return null;
        }
        if (!is_array($entry) || !is_string($entry['t'] ?? null) || $entry['t'] === '' || !is_numeric($entry['s'] ?? null)) {
            return null;
        }
        $staleAt = (float) $entry['s'];
        return $now < $staleAt ? [new AccessToken($entry['t']), $staleAt] : null;
    }

    private function writeCache(AccessToken $token, float $staleAt): void
    {
        $cache = $this->cache();
        if ($cache === null) {
            return;
        }
        $ttl = max(1, (int) floor($staleAt - $this->now()));
        try {
            $cache->set($this->cacheKey(), ['t' => $token->exposeSecret(), 's' => $staleAt], $ttl);
        } catch (\Throwable $e) {
            $this->logger->debug('the Lingara token cache failed on write: ' . $e::class);
        }
    }

    private function transport(): Transport
    {
        $transport = Secrets::get($this->handle, 'transport');
        return $transport instanceof Transport ? $transport : throw new \LogicException('the token source has no transport');
    }

    private function cache(): ?CacheInterface
    {
        $cache = Secrets::get($this->handle, 'cache');
        return $cache instanceof CacheInterface ? $cache : null;
    }

    private function now(): float
    {
        return (float) $this->retry->now()->format('U.u');
    }
}
