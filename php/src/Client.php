<?php

declare(strict_types=1);

namespace Lingara;

use Lingara\Events\EventFeed;
use Lingara\Events\EventTail;
use Lingara\Events\Generated\InboundEvent;
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
use Lingara\Model\DialogueTurnRequest;
use Lingara\Model\EmbedToken;
use Lingara\Model\EmbedTokenRequest;
use Lingara\Model\EventPage;
use Lingara\Model\InboundEventAccepted;
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
 * Omit both credentials for a client that calls only the four public
 * operations. `clock` and `sleeper` are testing seams: the clock is read for
 * token freshness and HTTP-date Retry-After values, and the sleeper is handed
 * every Retry-After wait, and every tail reconnect delay, in seconds.
 * `tailMaxFailures` bounds tailEvents()' consecutive failed reopens (K5a).
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
    /** @var \Closure(float): void */
    private readonly \Closure $sleeper;

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
        private readonly int $tailMaxFailures = 8,
    ) {
        $credentials = compact('clientId', 'clientSecret', 'authMethod', 'scopes', 'tokenCache');
        self::check($credentials, $tokenSource, $version, $streamIdleTimeout);
        if ($tailMaxFailures < 1) {
            throw new \InvalidArgumentException('tailMaxFailures must be at least 1');
        }
        $http ??= HttpStack::detect();
        $clock ??= new SystemClock();
        $sleeper ??= self::defaultSleeper(...);
        $logger ??= new ErrorLogLogger();
        $this->baseUrl = rtrim($baseUrl, '/');
        $this->handle = Secrets::handle();
        Secrets::put($this->handle, 'transport', $http->transport($streamIdleTimeout, $tokenRequestTimeout));
        $this->retry = new Retry($maxAttempts, $retryAfterCap, $clock, $sleeper);
        $this->sleeper = \Closure::fromCallable($sleeper);
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

    // ── The sixteen operations, over Operations ───────────────────────────

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
     * The API's AsyncAPI document, which lists its events, decoded as
     * objects. Needs no token.
     *
     * @return ApiResponse<\stdClass>
     */
    public function getAsyncApiDocument(): ApiResponse
    {
        return $this->json('getAsyncApiDocument', [], \stdClass::class);
    }

    /**
     * One page of events (scope events:read): the page as it was sent.
     * events() walks every page and parses each item into an Event.
     *
     * @param list<string>|null $types
     *
     * @return ApiResponse<EventPage>
     */
    public function listEvents(?string $cursor = null, ?string $start = null, ?array $types = null, ?int $limit = null): ApiResponse
    {
        return $this->json('listEvents', [], EventPage::class, EventFeed::query($cursor, $start, $types, $limit));
    }

    /**
     * Every event after `cursor`, page after page, to the end of the feed
     * (scope events:read; ADR 30.9.26aa D6). Without a cursor, `start` is
     * `latest` (the server's default) or `oldest`. Nothing is sent until the
     * feed is iterated.
     *
     * @param list<string>|null $types
     */
    public function events(?string $cursor = null, ?string $start = null, ?array $types = null): EventFeed
    {
        return new EventFeed($this->eventPage(...), $cursor, $start, $types);
    }

    /**
     * One connection to the events stream, under K5 (scope events:read): it
     * ends on `done`, unyielded, or throws its `error`. tailEvents() is the
     * stream that reconnects; this is the raw operation it is built on.
     *
     * @param list<string>|null $types
     */
    public function streamEvents(?string $cursor = null, ?string $start = null, ?array $types = null, ?string $lastEventId = null): EventStream
    {
        $headers = $lastEventId === null ? [] : ['Last-Event-ID' => $lastEventId];
        return $this->stream('streamEvents', [], null, EventFeed::query($cursor, $start, $types), $headers);
    }

    /**
     * Every event after `cursor`, live, reconnecting after every ending
     * (scope events:read; K5a, ADR 30.9.26aa D7). The cursor is sent as
     * Last-Event-ID, so `tailEvents(cursor: $feed->cursor())` takes over from
     * the feed with no gap. Nothing is sent until the tail is iterated.
     *
     * @param list<string>|null $types
     */
    public function tailEvents(?string $cursor = null, ?string $start = null, ?array $types = null): EventTail
    {
        // The first request's URL is every reopen's: Last-Event-ID, never a
        // cursor query, carries the position.
        $query = EventFeed::query(null, $cursor === null ? $start : null, $types);
        $open = fn(?string $lastEventId): EventStream => $this->stream(
            'streamEvents',
            [],
            null,
            $query,
            $lastEventId === null ? [] : ['Last-Event-ID' => $lastEventId],
            false,
        );
        return new EventTail($open, $cursor, $this->sleeper, $this->tailMaxFailures, $this->retry->retryAfterCap);
    }

    /**
     * Sends one event from the game (scope events:write, and
     * lesson_plans:write when it asks for generation; ADR 30.9.26aa D8).
     * Without `idempotencyKey`, a UUIDv4 is made once for this call and sent
     * on every K4 attempt, so a retried 429 or 503 gets the first answer.
     * Pass your own key to resend safely after a crash: a reused key returns
     * the first answer, whatever the body.
     *
     * @return ApiResponse<InboundEventAccepted>
     */
    public function sendEvent(InboundEvent $event, ?string $idempotencyKey = null): ApiResponse
    {
        $headers = ['Idempotency-Key' => $idempotencyKey ?? self::uuid4()];
        $payload = json_encode($event, JSON_THROW_ON_ERROR);
        return $this->json('sendEvent', [], InboundEventAccepted::class, [], $headers, $payload);
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
     * Mints a player's embed token (scope embed:mint; a metered client only,
     * else 403 embed_needs_metered). Call it on your server, never on a
     * player's device. Every call mints: nothing is cached, and K4 retries a
     * 429 or 503 with no Idempotency-Key, since two tokens are harmless.
     *
     * @return ApiResponse<MintedToken>
     */
    public function createEmbedToken(EmbedTokenRequest $request): ApiResponse
    {
        $payload = json_encode(ObjectSerializer::sanitizeForSerialization($request), JSON_THROW_ON_ERROR);
        $answer = $this->json('createEmbedToken', [], EmbedToken::class, [], [], $payload);
        return new ApiResponse(MintedToken::fromAnswer($answer->value), $answer->servedVersion);
    }

    /**
     * Deletes a player and revokes its tokens (scope embed:mint). An unknown
     * player is a success too, so a retry is safe, and it works while embed
     * is dark. The value is an empty \stdClass.
     *
     * @return ApiResponse<\stdClass>
     */
    public function deleteEmbedPlayer(string $playerRef): ApiResponse
    {
        return $this->noContent('deleteEmbedPlayer', [$playerRef]);
    }

    /**
     * Streams an NPC's reply to one line (scope embed:play: an embed token,
     * or a metered client's own token). Opened without K4's retries, since
     * each attempt spends NPC cells: a 429 or 503 is thrown at once with its
     * retryAfter(), and sending the turn again is the caller's choice. No
     * retry helps 403 embed_needs_metered or 422 safety_input_flagged ("say
     * something else"). The window is neither checked nor trimmed here: at
     * most 12 `history` entries, `line` and each entry at most 500
     * characters. Append the NPC's reply to `history` cut to its first 500.
     */
    public function sendDialogueTurn(DialogueTurnRequest $request): EventStream
    {
        return $this->stream('sendDialogueTurn', [], $request, [], [], false);
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
     * $model is the operation's success schema, as Operations names it, or
     * \stdClass for a bare `type: object`; ClientTest holds the two together.
     *
     * @template T of object
     *
     * @param list<string>          $args
     * @param class-string<T>       $model
     * @param array<string, string> $query
     * @param array<string, string> $headers
     *
     * @return ApiResponse<T>
     */
    private function json(string $operationId, array $args, string $model, array $query = [], array $headers = [], ?string $payload = null): ApiResponse
    {
        $operation = Operations::OPERATIONS[$operationId];
        $url = $this->url($operation['path'], $operation['pathParams'], $args, $query);
        [$response, $token] = $this->send($operationId, $url, $payload, $headers);
        $served = $this->deprecations->observe($response, $url);
        $body = $this->transport()->readAll($response->getBody(), $this->streamIdleTimeout, self::secrets($token));
        return new ApiResponse(Json::model($model, $body), $served);
    }

    /**
     * A 204 call, by name: a route's `response` null means an untyped JSON
     * object. Any 2xx body is discarded unread; the echo is still observed.
     *
     * @param list<string> $args
     *
     * @return ApiResponse<\stdClass>
     */
    private function noContent(string $operationId, array $args): ApiResponse
    {
        $operation = Operations::OPERATIONS[$operationId];
        $url = $this->url($operation['path'], $operation['pathParams'], $args);
        [$response] = $this->send($operationId, $url, null);
        $served = $this->deprecations->observe($response, $url);
        $response->getBody()->close();
        return new ApiResponse(new \stdClass(), $served);
    }

    /**
     * One listEvents page for EventFeed, decoded as objects but not as a
     * model: the feed parses each item into an Event itself.
     *
     * @param array<string, string> $query
     */
    private function eventPage(array $query): mixed
    {
        $url = $this->url(Operations::OPERATIONS['listEvents']['path'], [], [], $query);
        [$response, $token] = $this->send('listEvents', $url, null);
        $this->deprecations->observe($response, $url);
        $body = $this->transport()->readAll($response->getBody(), $this->streamIdleTimeout, self::secrets($token));
        try {
            return Json::decode($body);
        } catch (\JsonException) {
            throw new TransportException(TransportKind::MalformedResponse, 'the response body does not decode');
        }
    }

    /**
     * A stream, once its headers are in. $retry false bypasses K4's attempt
     * loop, as a tail's open does (K5a).
     *
     * @param list<string>          $args
     * @param array<string, string> $query
     * @param array<string, string> $headers
     */
    private function stream(
        string $operationId,
        array $args,
        ?ModelInterface $request,
        array $query = [],
        array $headers = [],
        bool $retry = true,
    ): EventStream {
        $operation = Operations::OPERATIONS[$operationId];
        $url = $this->url($operation['path'], $operation['pathParams'], $args, $query);
        $payload = $request === null ? null : json_encode(ObjectSerializer::sanitizeForSerialization($request), JSON_THROW_ON_ERROR);
        [$response, $token] = $this->send($operationId, $url, $payload, $headers, $retry);
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
     * (a fresh budget for the retried request; none when $retry is false),
     * and the refusal mapping. $headers go on every attempt, so an
     * Idempotency-Key is the same on each (ADR 30.9.26aa D8). Returns a 2xx
     * response and the token it carried. A client with no token source sends
     * an operation that needs one without Authorization; the server's 401 is
     * the answer.
     *
     * No closure here captures a raw token: a closure's captured variables
     * show in any dump of a stack frame that holds it.
     *
     * @param array<string, string> $headers
     *
     * @return array{ResponseInterface, ?AccessToken}
     *
     * @throws LingaraException
     */
    private function send(string $operationId, string $url, ?string $payload, array $headers = [], bool $retry = true): array
    {
        $operation = Operations::OPERATIONS[$operationId];
        $tokens = $operation['needsToken'] ? $this->tokenSource : null;
        $token = $tokens?->token();
        $call = ['url' => $url, 'payload' => $payload, 'headers' => $headers];
        $response = $this->attempts($retry, fn(): ResponseInterface => $this->attempt($operation, $call, $token));
        if ($tokens !== null && $token !== null && $response->getStatusCode() === 401) {
            $response->getBody()->close();
            $tokens->invalidate($token);
            $token = $tokens->token();
            $response = $this->attempts($retry, fn(): ResponseInterface => $this->attempt($operation, $call, $token));
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

    /** @param callable(): ResponseInterface $attempt */
    private function attempts(bool $retry, callable $attempt): ResponseInterface
    {
        return $retry ? $this->retry->run($attempt) : $attempt();
    }

    /**
     * @param array{method: string, stream: mixed}                                $operation
     * @param array{url: string, payload: ?string, headers: array<string, string>} $call
     */
    private function attempt(array $operation, array $call, ?AccessToken $token): ResponseInterface
    {
        $request = $this->transport()->request($operation['method'], $call['url'])
            ->withHeader('Accept', $operation['stream'] === null ? 'application/json' : 'text/event-stream')
            ->withHeader('User-Agent', $this->userAgent);
        foreach ($call['headers'] as $name => $value) {
            $request = $request->withHeader($name, $value);
        }
        if ($call['payload'] !== null) {
            $request = $request->withHeader('Content-Type', 'application/json')->withBody($this->transport()->body($call['payload']));
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
     * but for A–Z a–z 0–9 - . _ ~, then the query, if any, encoded alike.
     *
     * @param list<string>          $names
     * @param list<string>          $args
     * @param array<string, string> $query
     */
    private function url(string $path, array $names, array $args, array $query = []): string
    {
        foreach ($names as $i => $name) {
            $value = $args[$i] ?? '';
            if ($value === '') {
                throw new \InvalidArgumentException("{$name} must be a non-empty string");
            }
            $path = str_replace("{{$name}}", rawurlencode($value), $path);
        }
        return $this->baseUrl . $path . ($query === [] ? '' : '?' . http_build_query($query, '', '&', PHP_QUERY_RFC3986));
    }

    /** A UUIDv4 from the platform CSPRNG, for an Idempotency-Key (K4; ADR 30.9.26aa D8). */
    private static function uuid4(): string
    {
        $bytes = random_bytes(16);
        $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40);
        $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80);
        return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($bytes), 4));
    }
}
