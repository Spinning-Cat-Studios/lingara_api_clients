<?php

declare(strict_types=1);

namespace Lingara;

use Lingara\Exception\LingaraException;
use Lingara\Exception\TransportException;
use Lingara\Exception\TransportKind;
use Lingara\Internal\Deprecations;
use Lingara\Internal\ErrorLogLogger;
use Lingara\Internal\ErrorMapper;
use Lingara\Internal\Json;
use Lingara\Internal\Operations;
use Lingara\Internal\Retry;
use Lingara\Internal\Secrets;
use Lingara\Internal\SystemClock;
use Lingara\Internal\Transport;
use Lingara\Internal\UserAgent;
use Lingara\Model\LessonPlan;
use Lingara\Model\LessonPlanCreateRequest;
use Lingara\Model\ModelInterface;
use Lingara\Model\TutorTurnRequest;
use Lingara\Model\Usage;
use Lingara\Model\VersionDetail;
use Lingara\Model\VersionList;
use Lingara\Model\VocabRequest;
use Psr\Clock\ClockInterface;
use Psr\Http\Message\ResponseInterface;
use Psr\Log\LoggerInterface;
use Psr\SimpleCache\CacheInterface;

/**
 * Calls the Lingara API. Every option is a named argument, and durations are
 * float seconds:
 *
 *     $client = new Client(
 *         clientId: getenv('LINGARA_CLIENT_ID') ?: null,
 *         clientSecret: getenv('LINGARA_CLIENT_SECRET') ?: null,
 *         tokenCache: $psr16Cache,   // strongly advised under PHP-FPM
 *     );
 *     $usage = $client->getUsage()->value;
 *
 * Omit both credentials for a client that calls only the three public
 * operations. `clock` and `sleeper` are testing seams: the clock is read for
 * token freshness and HTTP-date Retry-After values, and the sleeper is handed
 * every Retry-After wait in seconds.
 */
final class Client
{
    public const DEFAULT_BASE_URL = 'https://api.getlingara.com';
    public const DEFAULT_TOKEN_URL = 'https://api.getlingara.com/oauth/token';

    public readonly ?TokenSource $tokenSource;
    private readonly string $baseUrl;
    /** Keys the Transport in Secrets: an HTTP client may keep its last request, Authorization included. */
    private readonly object $handle;
    private readonly Retry $retry;
    private readonly Deprecations $deprecations;
    private readonly string $userAgent;

    /**
     * @param list<string>|null                    $scopes
     * @param (callable(DeprecationNotice): void)|null $onDeprecation
     * @param (callable(float): void)|null         $sleeper
     *
     * @throws \InvalidArgumentException for options that contradict each other
     */
    public function __construct(
        ?string $clientId = null,
        #[\SensitiveParameter]
        ?string $clientSecret = null,
        ?AuthMethod $authMethod = null,
        ?array $scopes = null,
        ?TokenSource $tokenSource = null,
        ?CacheInterface $tokenCache = null,
        string $baseUrl = self::DEFAULT_BASE_URL,
        string $tokenUrl = self::DEFAULT_TOKEN_URL,
        private readonly ?string $version = null,
        ?callable $onDeprecation = null,
        ?LoggerInterface $logger = null,
        int $maxAttempts = 3,
        float $retryAfterCap = 60.0,
        private readonly float $streamIdleTimeout = 120.0,
        float $tokenRequestTimeout = 30.0,
        ?string $userAgentSuffix = null,
        ?ClockInterface $clock = null,
        ?callable $sleeper = null,
        ?HttpStack $http = null,
    ) {
        $credentials = compact('clientId', 'clientSecret', 'authMethod', 'scopes', 'tokenCache');
        self::check($credentials, $tokenSource, $version, $streamIdleTimeout);
        $http ??= HttpStack::detect();
        $clock ??= new SystemClock();
        $sleeper ??= self::defaultSleeper(...);
        $logger ??= new ErrorLogLogger();
        $this->baseUrl = rtrim($baseUrl, '/');
        $this->handle = Secrets::handle();
        Secrets::put($this->handle, 'transport', $http->transport($streamIdleTimeout, $tokenRequestTimeout));
        $this->retry = new Retry($maxAttempts, $retryAfterCap, $clock, $sleeper);
        $this->deprecations = new Deprecations($onDeprecation, $logger);
        $this->userAgent = UserAgent::build($userAgentSuffix);
        $this->tokenSource = $tokenSource ?? ($clientId === null || $clientSecret === null ? null : new ClientCredentials(
            $clientId,
            $clientSecret,
            $authMethod ?? AuthMethod::Basic,
            $scopes ?? [],
            $tokenCache,
            $tokenUrl,
            $http,
            $maxAttempts,
            $retryAfterCap,
            $tokenRequestTimeout,
            $userAgentSuffix,
            $clock,
            $sleeper,
            $logger,
        ));
    }

    // ── The nine operations, over Operations ──────────────────────────────

    /** Streams a vocabulary list (scope vocab:generate). */
    public function generateVocabulary(VocabRequest $request): EventStream
    {
        return $this->stream('generateVocabulary', [], $request);
    }

    /** Streams a new lesson plan's generation (scope lesson_plans:write). A plan served from the library is a lone `result`. */
    public function createLessonPlan(LessonPlanCreateRequest $request): EventStream
    {
        return $this->stream('createLessonPlan', [], $request);
    }

    /**
     * A lesson plan by its id (scope lesson_plans:read).
     *
     * @return ApiResponse<LessonPlan>
     */
    public function getLessonPlan(string $id): ApiResponse
    {
        return $this->json('getLessonPlan', [$id], LessonPlan::class);
    }

    /** Rejoins a lesson plan's generation by its id (scope lesson_plans:read). */
    public function streamLessonPlan(string $id): EventStream
    {
        return $this->stream('streamLessonPlan', [$id], null);
    }

    /** Streams the tutor's reply to one turn (scope tutor:converse). */
    public function sendTutorMessage(TutorTurnRequest $request): EventStream
    {
        return $this->stream('sendTutorMessage', [], $request);
    }

    /**
     * This client's allowance, or its ledger if it is metered (scope usage:read).
     *
     * @return ApiResponse<Usage>
     */
    public function getUsage(): ApiResponse
    {
        return $this->json('getUsage', [], Usage::class);
    }

    /**
     * The API's OpenAPI document, decoded as objects. Needs no token.
     *
     * @return ApiResponse<\stdClass>
     */
    public function getOpenApiDocument(): ApiResponse
    {
        return $this->json('getOpenApiDocument', [], \stdClass::class);
    }

    /**
     * The API's versions. Needs no token.
     *
     * @return ApiResponse<VersionList>
     */
    public function listApiVersions(): ApiResponse
    {
        return $this->json('listApiVersions', [], VersionList::class);
    }

    /**
     * One API version, by its id. Needs no token.
     *
     * @return ApiResponse<VersionDetail>
     */
    public function getApiVersion(string $id): ApiResponse
    {
        return $this->json('getApiVersion', [$id], VersionDetail::class);
    }

    /**
     * The default sleeper: usleep in a loop to an hrtime() deadline, so a
     * signal that cuts one usleep short does not shorten the wait.
     *
     * @internal
     */
    public static function defaultSleeper(float $seconds): void
    {
        $until = Transport::now() + (int) ($seconds * 1e9);
        while (($left = $until - Transport::now()) > 0) {
            usleep((int) min($left / 1000, 1_000_000));
        }
    }

    /** @return array<string, mixed> */
    public function __debugInfo(): array
    {
        return ['baseUrl' => $this->baseUrl, 'version' => $this->version, 'tokenSource' => $this->tokenSource];
    }

    /** @return array<string, mixed> */
    public function __serialize(): array
    {
        throw new \LogicException('a Lingara client cannot be serialized');
    }

    /** @param array<mixed> $data */
    public function __unserialize(array $data): void
    {
        throw new \LogicException('a Lingara client cannot be unserialized');
    }

    /**
     * @param array<string, mixed> $credentials
     */
    private static function check(array $credentials, ?TokenSource $tokenSource, ?string $version, float $idle): void
    {
        $given = array_keys(array_filter($credentials, static fn(mixed $v): bool => $v !== null));
        if ($tokenSource !== null && $given !== []) {
            throw new \InvalidArgumentException('tokenSource cannot be combined with ' . implode(', ', $given));
        }
        if (($credentials['clientId'] === null) !== ($credentials['clientSecret'] === null)) {
            throw new \InvalidArgumentException('clientId and clientSecret are given together or not at all');
        }
        if ($version === '') {
            throw new \InvalidArgumentException('version must not be empty');
        }
        if ($idle <= 0) {
            throw new \InvalidArgumentException('streamIdleTimeout must be positive');
        }
    }

    /**
     * $model is the operation's `200` schema, as Operations names it, or
     * \stdClass for a bare `type: object`; ClientTest holds the two together.
     *
     * @template T of object
     *
     * @param list<string>    $args
     * @param class-string<T> $model
     *
     * @return ApiResponse<T>
     */
    private function json(string $operationId, array $args, string $model): ApiResponse
    {
        $operation = Operations::OPERATIONS[$operationId];
        $url = $this->url($operation['path'], $operation['pathParams'], $args);
        [$response, $token] = $this->send($operationId, $url, null);
        $served = $this->deprecations->observe($response, $url);
        $body = $this->transport()->readAll($response->getBody(), $this->streamIdleTimeout, self::secrets($token));
        return new ApiResponse($this->decodeResponse($model, $body), $served);
    }

    /** @param list<string> $args */
    private function stream(string $operationId, array $args, ?ModelInterface $request): EventStream
    {
        $operation = Operations::OPERATIONS[$operationId];
        $url = $this->url($operation['path'], $operation['pathParams'], $args);
        $payload = $request === null ? null : json_encode(ObjectSerializer::sanitizeForSerialization($request), JSON_THROW_ON_ERROR);
        [$response, $token] = $this->send($operationId, $url, $payload);
        if (ErrorMapper::mediaType($response) !== 'text/event-stream') {
            $response->getBody()->close();
            throw new TransportException(TransportKind::MalformedResponse, 'a 200 stream answered ' . ErrorMapper::mediaType($response));
        }
        // The deprecation hook runs after the headers, before the first event.
        $served = $this->deprecations->observe($response, $url);
        return new EventStream($response->getBody(), $operationId, $served, $this->transport(), $this->streamIdleTimeout, $token);
    }

    /**
     * Sends one call: its token and K1's one 401 retry, K4's Retry-After loop
     * (a fresh budget for the retried request), and the refusal mapping.
     * Returns a 2xx response and the token it carried. A client with no
     * token source sends an operation that needs one without Authorization;
     * the server's 401 is the answer.
     *
     * No closure here captures a raw token: a closure's captured variables
     * show in any dump of a stack frame that holds it.
     *
     * @return array{ResponseInterface, ?AccessToken}
     *
     * @throws LingaraException
     */
    private function send(string $operationId, string $url, ?string $payload): array
    {
        $operation = Operations::OPERATIONS[$operationId];
        $tokens = $operation['needsToken'] ? $this->tokenSource : null;
        $token = $tokens?->token();
        $response = $this->retry->run(fn(): ResponseInterface => $this->attempt($operation, $url, $payload, $token));
        if ($tokens !== null && $token !== null && $response->getStatusCode() === 401) {
            $response->getBody()->close();
            $tokens->invalidate($token);
            $token = $tokens->token();
            $response = $this->retry->run(fn(): ResponseInterface => $this->attempt($operation, $url, $payload, $token));
        }
        $status = $response->getStatusCode();
        if ($status < 200 || $status > 299) {
            $body = $this->transport()->readAll($response->getBody(), $this->streamIdleTimeout, self::secrets($token));
            throw ErrorMapper::refusal('v1', $response, $body, $this->retry->now());
        }
        return [$response, $token];
    }

    /**
     * @return list<string>
     *
     * @internal
     */
    public static function secrets(?AccessToken $token): array
    {
        return $token === null ? [] : [$token->exposeSecret()];
    }

    private function transport(): Transport
    {
        $transport = Secrets::get($this->handle, 'transport');
        return $transport instanceof Transport ? $transport : throw new \LogicException('the client has no transport');
    }

    /** @param array{method: string, stream: mixed} $operation */
    private function attempt(array $operation, string $url, ?string $payload, ?AccessToken $token): ResponseInterface
    {
        $request = $this->transport()->request($operation['method'], $url)
            ->withHeader('Accept', $operation['stream'] === null ? 'application/json' : 'text/event-stream')
            ->withHeader('User-Agent', $this->userAgent);
        if ($payload !== null) {
            $request = $request->withHeader('Content-Type', 'application/json')->withBody($this->transport()->body($payload));
        }
        if ($token !== null) {
            $request = $request->withHeader('Authorization', 'Bearer ' . $token->exposeSecret());
        }
        if ($this->version !== null) {
            $request = $request->withHeader('Lingara-Version', $this->version);
        }
        return $this->transport()->send($this->transport()->v1Client(), $request, $this->streamIdleTimeout, self::secrets($token));
    }

    /**
     * The base URL and the route's path, each path parameter percent-encoded
     * but for A–Z a–z 0–9 - . _ ~.
     *
     * @param list<string> $names
     * @param list<string> $args
     */
    private function url(string $path, array $names, array $args): string
    {
        foreach ($names as $i => $name) {
            $value = $args[$i] ?? '';
            if ($value === '') {
                throw new \InvalidArgumentException("{$name} must be a non-empty string");
            }
            $path = str_replace("{{$name}}", rawurlencode($value), $path);
        }
        return $this->baseUrl . $path;
    }

    /**
     * A JSON object body as $model. Any throw from decoding, the generated
     * setters included, is MalformedResponse: a generated model's
     * \InvalidArgumentException never reaches the caller.
     *
     * @template T of object
     *
     * @param class-string<T> $model
     *
     * @return T
     */
    private function decodeResponse(string $model, string $body): object
    {
        try {
            $value = Json::decode($body);
            $decoded = $value instanceof \stdClass && $model !== \stdClass::class
                ? ObjectSerializer::deserialize($value, $model)
                : $value;
        } catch (\Throwable) {
            $decoded = null;
        }
        if (!$decoded instanceof $model) {
            throw new TransportException(TransportKind::MalformedResponse, 'the response body does not decode');
        }
        return $decoded;
    }
}
