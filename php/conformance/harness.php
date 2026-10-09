<?php

declare(strict_types=1);

// The PHP library's conformance harness (conformance/README.md, Writing a
// harness; ADR 29.9.26u D7). Run through `conformance-server run`:
//
//   php php/conformance/harness.php --client=symfony | --client=guzzle
//
// --client chooses HttpStack::symfony() or HttpStack::guzzle(): with both
// installed as require-dev, detection could never pick Guzzle. Every client
// is built from the case's `client` block through Client's named arguments
// only, with a virtual clock and a recording sleeper. The library has no
// in-process concurrency, so a `parallel: n` step runs its n calls in
// sequence (C2 D10), and `cancel_after_events: n` breaks out of the foreach
// after the n-th event: PHP's native cancellation of a stream. The control
// surface is reached with file_get_contents, so the harness needs no HTTP
// library of its own. The events operations and the `events` and `tail`
// steps are events.php's (ADR 30.9.26aa D9). Not in the dist.

require __DIR__ . '/../vendor/autoload.php';
require __DIR__ . '/events.php';

use Lingara\ApiResponse;
use Lingara\AuthMethod;
use Lingara\Client;
use Lingara\DeprecationNotice;
use Lingara\Exception\ApiException;
use Lingara\Exception\LingaraException;
use Lingara\Exception\MaintenanceException;
use Lingara\Exception\OAuthException;
use Lingara\Exception\TransportException;
use Lingara\HttpStack;
use Lingara\Internal\Operations;
use Lingara\MintedToken;
use Lingara\Model\DialogueEntry;
use Lingara\Model\DialogueTurnRequest;
use Lingara\Model\EmbedTokenRequest;
use Lingara\Model\LessonPlanCreateRequest;
use Lingara\Model\Npc;
use Lingara\Model\Speaker;
use Lingara\Model\TutorTurnRequest;
use Lingara\Model\VocabRequest;
use Lingara\ObjectSerializer;
use Lingara\Version;
use Psr\Clock\ClockInterface;

const CLOCK_START = 1_790_000_000;

/**
 * The case being run. Held here, never passed down: a case carries its
 * client secret and its `redacted` strings, and an argument shows in the
 * trace of every error thrown beneath it, which the redaction check reads.
 */
final class Current
{
    /** @var array<string, mixed> */
    public static array $case = [];
}

/** One case's virtual clock, recording sleeper and recorded hook calls. */
final class Rig implements ClockInterface
{
    public float $now = CLOCK_START;

    /** @var list<float> */
    public array $sleeps = [];

    /** @var list<array<string, mixed>> */
    public array $hooks = [];

    public function now(): DateTimeImmutable
    {
        return new DateTimeImmutable('@' . sprintf('%.6F', $this->now));
    }

    public function sleep(float $seconds): void
    {
        $this->sleeps[] = $seconds;
    }

    public function hook(DeprecationNotice $notice): void
    {
        $this->hooks[] = [
            'version' => $notice->version,
            'deprecated_at' => $notice->deprecatedAt?->getTimestamp(),
            'sunset_at' => $notice->sunsetAt?->getTimestamp(),
            'link' => $notice->linkRaw === null ? null : ['raw' => $notice->linkRaw, 'target' => $notice->linkTarget],
        ];
    }
}

function stack(string $name): HttpStack
{
    return match ($name) {
        'symfony' => HttpStack::symfony(),
        'guzzle' => HttpStack::guzzle(),
        default => throw new InvalidArgumentException("--client must be symfony or guzzle, not {$name}"),
    };
}

/** @param array<string, mixed> $block */
function buildClient(array $block, Rig $rig, HttpStack $http, string $base, string $tokenUrl): Client
{
    $args = ['baseUrl' => $base, 'tokenUrl' => $tokenUrl, 'clock' => $rig, 'sleeper' => $rig->sleep(...), 'http' => $http];
    if (isset($block['credentials'])) {
        $cred = $block['credentials'];
        $args += ['clientId' => $cred['client_id'], 'clientSecret' => $cred['client_secret']];
        $args['authMethod'] = ($cred['auth'] ?? 'basic') === 'post' ? AuthMethod::Post : AuthMethod::Basic;
    }
    if (isset($block['scopes'])) {
        $args['scopes'] = $block['scopes'];
    }
    if (array_key_exists('version', $block)) {
        $args['version'] = $block['version'];
    }
    if (isset($block['stream_idle_timeout_ms'])) {
        $args['streamIdleTimeout'] = $block['stream_idle_timeout_ms'] / 1000;
    }
    if (($block['deprecation_hook'] ?? null) === 'record') {
        $args['onDeprecation'] = $rig->hook(...);
    }
    if (isset($block['retries']['max_attempts'])) {
        $args['maxAttempts'] = $block['retries']['max_attempts'];
    }
    if (isset($block['retries']['retry_after_cap_s'])) {
        $args['retryAfterCap'] = (float) $block['retries']['retry_after_cap_s'];
    }
    if (isset($block['user_agent_suffix'])) {
        $args['userAgentSuffix'] = $block['user_agent_suffix'];
    }
    return new Client(...$args);
}

// ── One call, as the harness saw it ──────────────────────────────────────

function invoke(Client $client, int $step): array
{
    $call = Current::$case['steps'][$step]['call'];
    $operation = $call['operation'];
    $id = (string) ($call['params']['id'] ?? '');
    $body = $call['body'] ?? [];
    try {
        return match ($operation) {
            'generateVocabulary' => consume($client->generateVocabulary(new VocabRequest($body)), $operation, $call['cancel_after_events'] ?? null),
            'createLessonPlan' => consume($client->createLessonPlan(new LessonPlanCreateRequest($body)), $operation, $call['cancel_after_events'] ?? null),
            'sendTutorMessage' => consume($client->sendTutorMessage(new TutorTurnRequest($body)), $operation, $call['cancel_after_events'] ?? null),
            'streamLessonPlan' => consume($client->streamLessonPlan($id), $operation, $call['cancel_after_events'] ?? null),
            'sendDialogueTurn' => consume($client->sendDialogueTurn(dialogueTurn($body)), $operation, $call['cancel_after_events'] ?? null),
            'createEmbedToken' => completed($client->createEmbedToken(new EmbedTokenRequest($body))),
            'deleteEmbedPlayer' => completed($client->deleteEmbedPlayer((string) ($call['params']['player_ref'] ?? '')), 204),
            'getLessonPlan' => completed($client->getLessonPlan($id)),
            'getApiVersion' => completed($client->getApiVersion($id)),
            'getUsage' => completed($client->getUsage()),
            'getOpenApiDocument' => completed($client->getOpenApiDocument()),
            'listApiVersions' => completed($client->listApiVersions()),
            default => invokeEvents($client, $call) ?? ['outcome' => "harness: no operation {$operation}"],
        };
    } catch (LingaraException $e) {
        return failed($e, []);
    }
}

/**
 * A case body as DialogueTurnRequest, its npc and history entries built as
 * their models: the array constructor would leave them arrays, and the
 * serializer's deserialize() would set every absent key to null, which
 * sanitizeForSerialization then sends.
 */
function dialogueTurn(array $body): DialogueTurnRequest
{
    $body['npc'] = new Npc($body['npc']);
    if (isset($body['history'])) {
        $body['history'] = array_map(
            static fn (array $entry): DialogueEntry => new DialogueEntry(['speaker' => Speaker::from($entry['speaker']), 'text' => $entry['text']]),
            $body['history'],
        );
    }
    return new DialogueTurnRequest($body);
}

/**
 * $status is the operation's success status: ApiResponse holds the body,
 * and every 2xx but sendEvent's 202 and deleteEmbedPlayer's 204 is a 200.
 * The result's renderings join the `redacted` scan (ADR 1.10.26w D7).
 *
 * @param ApiResponse<object> $response
 */
function completed(ApiResponse $response, int $status = 200): array
{
    $value = $response->value;
    $body = $value instanceof MintedToken ? wire($value) : plain($value);
    return [
        'outcome' => 'completed', 'status' => $status, 'body' => $body, 'served_version' => $response->servedVersion,
        'renderings' => renderings($response),
    ];
}

/** A MintedToken in wire form, through its one exposing accessor: snake_case, expires_at as received, expires_in in seconds. */
function wire(MintedToken $token): array
{
    return [
        'token' => $token->token->exposeSecret(), 'expires_at' => $token->expiresAt, 'expires_in' => $token->expiresIn,
        'subject' => $token->subject, 'scopes' => $token->scopes, 'account_linked' => $token->accountLinked,
    ];
}

/** Drains a stream; after `cancel_after_events` events it breaks out, which closes the connection. */
function consume(Lingara\EventStream $stream, string $operation, ?int $cancelAfter): array
{
    $names = [];
    foreach (Operations::OPERATIONS[$operation]['stream']['events'] as $name => $event) {
        if ($event['class'] !== null) {
            $names[$event['class']] = $name;
        }
    }
    $seen = [];
    try {
        foreach ($stream as $event) {
            $seen[] = ['event' => $names[$event::class] ?? $event::class, 'data' => plain($event->data)];
            if (count($seen) === $cancelAfter) {
                break;
            }
        }
    } catch (LingaraException $e) {
        return failed($e, $seen);
    }
    $cancelled = $cancelAfter !== null && count($seen) === $cancelAfter;
    return [
        'outcome' => $cancelled ? 'cancelled' : 'completed',
        'status' => $cancelled ? null : 200,
        'events' => $seen,
        'served_version' => $stream->servedVersion(),
    ];
}

function failed(LingaraException $e, array $events): array
{
    [$variant, $fields] = match (true) {
        $e instanceof ApiException => ['ApiError', [
            'status' => $e->status(), 'code' => $e->errorCode(), 'message' => $e->getMessage(),
            'retry_after' => $e->retryAfter(), 'plan_id' => $e->planId(), 'served_version' => $e->servedVersion(),
        ]],
        $e instanceof OAuthException => ['OAuthError', [
            'status' => $e->status(), 'error' => $e->error(), 'description' => $e->description(), 'retry_after' => $e->retryAfter(),
        ]],
        $e instanceof MaintenanceException => ['MaintenanceError', ['body' => $e->body(), 'retry_after' => $e->retryAfter()]],
        $e instanceof TransportException => ['TransportError', ['kind' => $e->kind()->value]],
        default => ['not a known variant', ['debug' => $e::class]],
    };
    return [
        'outcome' => 'error', 'events' => $events, 'variant' => $variant, 'fields' => $fields,
        'served_version' => $e instanceof ApiException ? $e->servedVersion() : null, 'renderings' => renderings($e),
    ];
}

/** Every rendering PHP offers of an object: print_r, var_export, var_dump, json_encode, and for an error its message, string form and whole previous chain. */
function renderings(object $object): array
{
    $out = [];
    for ($link = $object; $link !== null; $link = $link instanceof Throwable ? $link->getPrevious() : null) {
        ob_start();
        var_dump($link);
        $out[] = (string) ob_get_clean();
        $out[] = print_r($link, true);
        $out[] = (string) @var_export($link, true);
        $out[] = (string) json_encode($link);
        if ($link instanceof Throwable) {
            $out[] = $link->getMessage();
            $out[] = (string) $link;
        }
    }
    return $out;
}

// ── Comparison (conformance/README.md, Comparison rules) ─────────────────

/** Plain JSON data: models through the serializer, null-valued keys dropped, objects as arrays. */
function plain(mixed $value): mixed
{
    if (is_object($value)) {
        $value = ObjectSerializer::sanitizeForSerialization($value);
        $value = json_decode((string) json_encode($value), true);
    }
    if (!is_array($value)) {
        return $value;
    }
    $out = [];
    foreach ($value as $key => $item) {
        if ($item !== null) {
            $out[$key] = plain($item);
        }
    }
    return array_is_list($value) ? array_values($out) : $out;
}

function canon(mixed $value): string
{
    return (string) json_encode(sortKeys(plain($value)), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}

function sortKeys(mixed $value): mixed
{
    if (!is_array($value)) {
        return $value;
    }
    $value = array_map('sortKeys', $value);
    if (!array_is_list($value)) {
        ksort($value, SORT_STRING);
    }
    return $value;
}

function substitute(mixed $value, string $base): mixed
{
    return match (true) {
        is_string($value) => str_replace('{base_url}', $base, $value),
        is_array($value) => array_map(static fn (mixed $v): mixed => substitute($v, $base), $value),
        default => $value,
    };
}

/** @return list<string> */
function compare(array $expect, array $seen): array
{
    $out = [];
    if ($seen['outcome'] !== $expect['outcome']) {
        $detail = isset($seen['variant']) ? " ({$seen['variant']} " . canon($seen['fields']) . ')' : '';
        $out[] = "outcome: expected {$expect['outcome']}, got {$seen['outcome']}{$detail}";
    }
    $got = [
        'status' => $seen['status'] ?? ($seen['fields']['status'] ?? null), 'body' => $seen['body'] ?? null,
        'events' => $seen['events'] ?? [], 'served_version' => $seen['served_version'] ?? null,
        'sleeps_s' => $seen['sleeps'], 'hook_calls' => $seen['hooks'],
    ];
    foreach ($got as $label => $value) {
        if (array_key_exists($label, $expect) && canon($expect[$label]) !== canon($value)) {
            $out[] = "{$label}: expected " . canon($expect[$label]) . ', got ' . canon($value);
        }
    }
    if (isset($expect['error'])) {
        array_push($out, ...compareError($expect['error'], $seen));
    }
    foreach ($expect['redacted'] ?? [] as $secret) {
        foreach ($seen['renderings'] ?? [] as $rendering) {
            if ($secret !== '' && str_contains($rendering, $secret)) {
                $out[] = 'redacted: a rendering contains ' . substr($secret, 0, 12) . '…';
                break;
            }
        }
    }
    return $out;
}

/** @return list<string> */
function compareError(array $want, array $seen): array
{
    if (!isset($seen['variant'])) {
        return ["error: expected {$want['variant']}, got none"];
    }
    $out = [];
    if ($seen['variant'] !== $want['variant']) {
        $out[] = "error.variant: expected {$want['variant']}, got {$seen['variant']}";
    }
    foreach ($want['fields'] ?? [] as $name => $value) {
        if (canon($value) !== canon($seen['fields'][$name] ?? null)) {
            $out[] = "error.{$name}: expected " . canon($value) . ', got ' . canon($seen['fields'][$name] ?? null);
        }
    }
    return $out;
}

// ── The case loop ────────────────────────────────────────────────────────

/** @return list<string> */
function runStep(Rig $rig, Client $client, int $step, string $base): array
{
    $call = Current::$case['steps'][$step]['call'];
    $n = $call['parallel'] ?? 1;
    $out = [];
    $rig->sleeps = [];
    $rig->hooks = [];
    $runs = [];
    for ($i = 0; $i < $n; $i++) {
        $runs[] = invoke($client, $step);
    }
    $expect = substitute(Current::$case['steps'][$step]['expect'], $base);
    foreach ($runs as $i => $seen) {
        $seen['sleeps'] = array_map(static fn (float $s): int => (int) round($s), $rig->sleeps);
        $seen['hooks'] = $rig->hooks;
        $seen['renderings'] = array_merge($seen['renderings'] ?? [], renderings($client));
        if ($client->tokenSource !== null) {
            $seen['renderings'] = array_merge($seen['renderings'], renderings($client->tokenSource));
        }
        $label = $n > 1 ? 'call ' . ($i + 1) . ': ' : '';
        foreach (compare($expect, $seen) as $mismatch) {
            $out[] = "{$call['operation']}: {$label}{$mismatch}";
        }
    }
    return $out;
}

/** @return list<string> an `events` or `tail` step's mismatches */
function runHelperStep(Rig $rig, Client $client, int $step, string $base): array
{
    $rig->sleeps = [];
    $rig->hooks = [];
    $seen = runHelper($client, Current::$case['steps'][$step]);
    $seen['sleeps'] = array_map(static fn (float $s): int => (int) round($s), $rig->sleeps);
    $seen['hooks'] = $rig->hooks;
    $expect = substitute(Current::$case['steps'][$step]['expect'], $base);
    $label = isset(Current::$case['steps'][$step]['tail']) ? 'tail' : 'events';
    return array_map(static fn (string $m): string => "{$label}: {$m}", [...compare($expect, $seen), ...compareHelper($expect, $seen)]);
}

/** @return list<string> */
function steps(array $env, HttpStack $http): array
{
    try {
        $block = Current::$case['client'] ?? [];
        [$base, $tokenUrl] = urls($env, $block);
        $rig = new Rig();
        $client = buildClient($block, $rig, $http, $base, $tokenUrl);
        unset($block);
        $out = [];
        foreach (Current::$case['steps'] ?? [] as $i => $step) {
            if (isset($step['advance_clock_s'])) {
                $rig->now += $step['advance_clock_s'];
            }
            if (isset($step['call'], $step['expect'])) {
                array_push($out, ...runStep($rig, $client, $i, $env['base']));
            }
            if ((isset($step['events']) || isset($step['tail'])) && isset($step['expect'])) {
                array_push($out, ...runHelperStep($rig, $client, $i, $env['base']));
            }
        }
        return $out;
    } catch (Throwable $e) {
        return ['harness: ' . $e::class . ': ' . $e->getMessage()];
    }
}

/** The case server, or for `base_url: unreachable` a port bound and released so nothing listens. */
function urls(array $env, array $block): array
{
    if (($block['base_url'] ?? null) !== 'unreachable') {
        return [$env['base'], $env['token']];
    }
    $server = stream_socket_server('tcp://127.0.0.1:0');
    $name = (string) stream_socket_get_name($server, false);
    fclose($server);
    $base = 'http://' . $name;
    return [$base, "{$base}/oauth/token"];
}

function control(string $method, string $url): mixed
{
    $context = stream_context_create(['http' => ['method' => $method, 'ignore_errors' => true, 'timeout' => 120]]);
    $body = file_get_contents($url, false, $context);
    $status = (int) explode(' ', $http_response_header[0] ?? 'HTTP/1.1 0')[1];
    if ($body === false || $status < 200 || $status > 299) {
        throw new RuntimeException("{$url}: {$status} {$body}");
    }
    return json_decode($body, true, 512, JSON_THROW_ON_ERROR);
}

function runCase(array $env, string $id, HttpStack $http): bool
{
    $started = hrtime(true);
    Current::$case = control('GET', "{$env['control']}/cases/{$id}");
    control('POST', "{$env['control']}/cases/{$id}/arm");
    $client = steps($env, $http);
    $server = control('POST', "{$env['control']}/cases/{$id}/finish")['mismatches'] ?? [];
    $pass = $client === [] && $server === [];
    $line = [
        'case' => $id, 'lang' => 'php', 'library_version' => Version::VERSION, 'result' => $pass ? 'pass' : 'fail',
        'client_mismatches' => $client, 'server_mismatches' => $server,
        'duration_ms' => (int) round((hrtime(true) - $started) / 1e6),
    ];
    file_put_contents($env['out'], json_encode($line, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . "\n", FILE_APPEND);
    if (!$pass) {
        fwrite(STDERR, "✗ {$id}: client " . json_encode($client, JSON_UNESCAPED_UNICODE) . ' server ' . json_encode($server, JSON_UNESCAPED_UNICODE) . "\n");
    }
    return $pass;
}

function env(): array
{
    $names = ['base' => 'LINGARA_CONFORMANCE_BASE_URL', 'token' => 'LINGARA_CONFORMANCE_TOKEN_URL',
        'control' => 'LINGARA_CONFORMANCE_CONTROL_URL', 'out' => 'LINGARA_CONFORMANCE_OUT'];
    $env = [];
    foreach ($names as $key => $name) {
        $value = getenv($name);
        if ($value === false) {
            fwrite(STDERR, "✗ harness: {$name} is not set: run this through conformance-server run\n");
            exit(1);
        }
        $env[$key] = $value;
    }
    $only = array_filter(array_map('trim', explode(',', (string) getenv('LINGARA_CONFORMANCE_ONLY'))));
    return $env + ['only' => array_values($only)];
}

function main(): void
{
    $name = getopt('', ['client:'])['client'] ?? 'symfony';
    $http = stack(is_string($name) ? $name : 'symfony');
    // C2 D13's line has no client field, so the log says which run this is.
    fwrite(STDERR, "client: {$http->name()}\n");
    $env = env();
    $ids = control('GET', "{$env['control']}/cases");
    if ($env['only'] !== []) {
        $ids = array_values(array_intersect($ids, $env['only']));
    }
    $all = true;
    foreach ($ids as $id) {
        $all = runCase($env, $id, $http) && $all;
    }
    exit($all ? 0 : 1);
}

main();
