# spinningcatstudios/lingara

The official PHP library for the [Lingara](https://getlingara.com) API. It
requires PSR interfaces only, so it works with the HTTP client your
application already has, and it streams generated vocabulary, lesson plans
and tutor replies as `foreach` loops.

PHP 8.2 or newer.

## Install

```sh
composer require spinningcatstudios/lingara symfony/http-client nyholm/psr7
```

`symfony/http-client` with `nyholm/psr7` is the recommended HTTP stack.
`guzzlehttp/guzzle` 7 works too, with the `allow_url_fopen` ini setting on
(see [HTTP clients](#http-clients)).

## Quick start

```php
use Lingara\Client;
use Lingara\Model\VocabRequest;
use Lingara\Stream\GenerateVocabularyEvent;

$client = new Client(
    clientId: getenv('LINGARA_CLIENT_ID') ?: null,
    clientSecret: getenv('LINGARA_CLIENT_SECRET') ?: null,
    tokenCache: $psr16Cache,          // optional; strongly advised under PHP-FPM
);

$usage = $client->getUsage();         // ApiResponse: ->value, ->servedVersion

$request = new VocabRequest(['level' => 2, 'source_lang' => 'en', 'target_lang' => 'zh']);
foreach ($client->generateVocabulary($request) as $event) {
    if ($event instanceof GenerateVocabularyEvent\Item) {
        echo $event->data->getWord(), "\n";
    }
}
```

Omit both credentials for a client that calls only the four public
operations: `getOpenApiDocument`, `getAsyncApiDocument`, `listApiVersions`
and `getApiVersion`.

Every option is a named argument of `new Client(...)`: `authMethod`,
`scopes`, `tokenSource`, `tokenCache`, `baseUrl`, `tokenUrl`, `version`,
`onDeprecation`, `logger`, `maxAttempts`, `retryAfterCap`,
`streamIdleTimeout`, `tokenRequestTimeout`, `userAgentSuffix`, `clock`,
`sleeper`, `http` and `tailMaxFailures`. Durations are `float` seconds.

## Streams

A stream method sends its request and reads the response headers before it
returns, so a refusal (a `401` after its one retry, a `429`, any other
non-2xx) is thrown by the call. It returns an `EventStream`, which you
iterate once; an in-stream failure, or the stream's `error` event, is
thrown from inside the `foreach`.

Leaving the loop by any path (`break`, `return`, an exception) closes the
connection. A stream you never iterate is closed when its last reference
goes, which in a long-lived process (a queue worker, RoadRunner) may be
later than you think, so close it yourself when your code may not reach the
loop:

```php
$stream = $client->streamLessonPlan($planId);
try {
    // … code that may return before the loop …
    foreach ($stream as $event) {
        // …
    }
} finally {
    $stream->close();
}
```

## Webhooks and events

Lingara tells your game when something happens (a lesson plan is ready, a
usage threshold is reached) through three doors that carry one envelope:
a signed webhook, a feed you page through, and a live tail. Each event is a
`Lingara\Events\Event`: one class per type under `Lingara\Events\Generated\`
(`LessonPlanReady`, `LessonPlanFailed`, …), each with `id`, `type`,
`createdAt`, `apiVersion`, `subject` and a typed `data`.

**Verify the raw body first.** `Webhook` checks the Standard Webhooks
signature against your `lgr_whsec_…` secret, then parses the body. Pass the
body exactly as it arrived (`file_get_contents('php://input')`, or
`$request->getContent()` in Symfony and Laravel), never a decoded and
re-encoded one, which no longer matches its signature. The headers are an
array, such as `getallheaders()`, or any PSR-7 request:

```php
use Lingara\Events\Webhook;
use Lingara\Events\VerificationException;

$webhook = new Webhook(getenv('LINGARA_WEBHOOK_SECRET')); // or [$new, $old] during a rotation
try {
    $event = $webhook->verify(file_get_contents('php://input'), getallheaders());
} catch (VerificationException $e) {
    http_response_code(400);                              // $e->reason() says why
    exit;
}
http_response_code(204);
```

`VerificationException` is deliberately not a `LingaraException`, so a
catch-all around your API calls never swallows a forged delivery. Answer
`2xx` fast and do the work afterwards, and deduplicate by `$event->id`:
delivery is at least once and unordered. An event type newer than this
library arrives as `UnknownEvent`, with its `data` as decoded JSON:
acknowledge it like any other, or Lingara keeps redelivering it for about a
day, and log it, or you lose it. `verifySignature()` checks the signature
alone, for a signed body that is not an event.

**The feed.** `events()` walks every page after a cursor and stops at the
end; it never sleeps or polls. Save `cursor()` and pass it next time.
Without a cursor it starts from now, or from the oldest retained event with
`start: 'oldest'`. A cursor older than the 30-day window is an
`ApiException` with `errorCode()` `cursor_expired`: start again without one.

```php
$feed = $client->events(cursor: $saved);
foreach ($feed as $event) {
    // …
}
$saved = $feed->cursor();
```

`listEvents()` is the single page it is built on.

**The tail.** `tailEvents()` streams events live and reconnects after every
ending, resuming from its `cursor()`, so it never ends on its own: leave the
`foreach` to stop it. It throws after `tailMaxFailures` (8) failed
reconnects in a row, about 90 seconds of backoff; catch that and restart
from `cursor()` to ride out a longer outage. The feed's cursor and the
tail's are one token, so `tailEvents(cursor: $feed->cursor())` takes over
from a catch-up with no gap. `streamEvents()` is the one connection it is
built on.

**Sending.** `sendEvent()` tells Lingara what happened in your game:

```php
use Lingara\Events\Generated\InboundEvent;
use Lingara\Model\WorldContextChanged;

$accepted = $client->sendEvent(InboundEvent::worldContextChanged(new WorldContextChanged([
    'scene' => 'A night market after rain', 'source_lang' => 'en', 'target_lang' => 'zh', 'level' => 2, 'generate' => true,
])))->value;
```

Each call carries an `Idempotency-Key`: a fresh UUIDv4 unless you pass
`idempotencyKey:`, and the same one on every retry of that call. Pass your
own when your game may resend after a crash, because a generated key is gone
once the call returns. A reused key returns the first answer, whatever the
body, so never reuse one for a different event. Only
`reaction.plan_status == generating` promises a `lesson_plan.ready` or
`lesson_plan.failed`; a `partial` or `complete` plan is readable now, though
an event for it may still arrive.

**Pin the version.** `data` is rendered at your client's pinned API
version, and this library's models were generated for
`Lingara\Version::GENERATED_FOR_VERSION`: pin your OAuth client to that
version.

## Embedding Lingara

A game or website can vouch for its own players: your server mints each
player a short-lived embed token, and the player's device uses it.

```php
use Lingara\Model\EmbedTokenRequest;

$minted = $client->createEmbedToken(new EmbedTokenRequest(['player_ref' => 'player-1001']))->value;
// store $minted->subject; hand $minted->token->exposeSecret() (lgr_et_…) to the device
```

- **Mint on your server, never on the player's device**, from a **metered**
  client holding `embed:mint` (list it in `scopes:` if you pass any).
  Otherwise the answer is a `403` `insufficient_scope` or
  `embed_needs_metered`, thrown as an `ApiException`. **Store `subject`**
  beside your player: it is the player's stable `lgr_sub_`, and how every
  event names them.
- `createEmbedToken()` returns a `Lingara\MintedToken`. Its `token` is an
  `AccessToken`: `var_dump`, `print_r` and `serialize` render it
  `[REDACTED]`, and `$minted->token->exposeSecret()` reads it. The token lives
  900 s and Lingara never refreshes it, so mint again when the player kit
  asks. `expiresIn` (seconds) is there for a device whose clock cannot be
  trusted; `expiresAt` is the server's RFC 3339 string. Nothing is cached:
  every call mints.
- `deleteEmbedPlayer($playerRef)` deletes a player and revokes their tokens.
  An unknown player is still a success, so it is idempotent, and it keeps
  working while embedding is switched off for your client. The answer has no
  body: the `ApiResponse`'s `value` is an empty `\stdClass`, and
  `servedVersion` is set as usual. Any `player_ref` is sent as one path
  segment, `/` included.
- `sendDialogueTurn()` streams an NPC's reply, `delta` by `delta`. The window
  is yours: at most 12 `history` entries, `line` and each entry at most 500
  characters, and no total cap; the library neither checks nor trims it. Send
  each NPC reply back cut to its first 500 characters. A turn is **never
  retried**: each attempt spends the player's NPC cells and your metered
  cells, so a `429` or `503` is thrown at once with its `retryAfter()`, and
  you decide whether to send it again. No retry helps
  `403 embed_needs_metered`, or `422 safety_input_flagged`, which means say
  something else.
- `PracticeCompleted` arrives through the webhook, the feed and the tail when
  your client holds `events:read` and `embed:play`; its `subject` names the
  player. `InboundEvent::worldPracticeCompleted()` sends one, with
  `events:write` and `embed:play`.
- **A player-side caller** supplies its embed token through a custom
  `TokenSource` (`tokenSource:`, returning `new AccessToken($embedToken)`);
  no client secret is involved there. The library never sends
  `X-Lingara-Embed-Origin`, so a token minted with an `origin` belongs to the
  browser widget: a game mints without one.

The [embed kits](https://github.com/Spinning-Cat-Studios/lingara_embeddable_sdk)
build on these calls: server kits (`spinningcatstudios/lingara-embed` for
PHP) for higher-level minting and webhook helpers, and player kits for the
engines.

## HTTP clients

PSR-18 does not say whether a response body streams, and it has no
per-request timeouts. So the library builds the two clients it has proven,
and lets you bring any other on your own terms. Pass one as `http:`:

| `http:` | Events arrive as sent | `streamIdleTimeout` | `tokenRequestTimeout` |
|---|---|---|---|
| `HttpStack::detect()`, the default | Symfony if installed, else Guzzle | enforced | enforced |
| `HttpStack::symfony()` (`symfony/http-client` + `nyholm/psr7`) | yes | enforced | enforced |
| `HttpStack::guzzle()` (Guzzle 7, `allow_url_fopen` on) | yes, within 0.1 s | enforced | enforced |
| `HttpStack::custom($client, $requestFactory, $streamFactory)` | only if your client streams | only if your client returns from a stalled read | not enforced |

With `custom()` the library uses your client exactly as given, for both the
API and the token endpoint. PHP cannot interrupt a blocking `sendRequest()`
or `read()` from inside the process, so streaming and both timeouts are
then your client's. A default-configured Guzzle client downloads the whole
body before `sendRequest()` returns: every event arrives at once, when the
stream ends, and a held stream never returns. Guzzle streams only when the
client is built with `['stream' => true]` and `allow_url_fopen` is on.

The library does not support clients that suspend a fiber inside
`sendRequest()` (AMPHP or Revolt bridges) or Swoole's coroutine hooks.

## Tokens and PHP-FPM

PHP-FPM discards the object graph after every web request, so an in-memory
token cache mints a token per request, and the token endpoint allows 60
mints per hour per client. Pass any PSR-16 cache as `tokenCache` and every
worker shares one token until shortly before it expires. The cost is that
an access token sits in your cache store. The cache is an optimisation,
never a failure: a backend that is down is a miss, and the call goes on.

The client secret and every token are kept out of `var_dump`, `print_r`,
`var_export`, `json_encode` and error messages. `exposeSecret()` is the one
accessor that returns a raw value.

## Errors

Every failure a call throws implements `Lingara\Exception\LingaraException`:

| Class | When |
|---|---|
| `ApiException` | an API refusal, or a stream's `error` event (then `status()` is 200) |
| `OAuthException` | the token endpoint refused the credentials or the scopes |
| `MaintenanceException` | a plain-text `503`: the API is under maintenance |
| `TransportException` | no usable answer; `kind()` says why: `Connect`, `Tls`, `Reset`, `Timeout`, `StreamEndedEarly`, `MalformedResponse`, `MalformedEvent` |

A `429` or `503` with a `Retry-After` of at most `retryAfterCap` (60 s) is
retried up to `maxAttempts` (3) times in all.

## Versions and deprecation

Pin an API version with `version:`. When a response comes from a deprecated
version, `onDeprecation` receives a `DeprecationNotice`; with no hook, one
warning per version goes to `logger`, a PSR-3 logger that defaults to
`error_log()`.

## Testing seams

`clock` (a PSR-20 clock) and `sleeper` (`callable(float $seconds): void`)
exist for tests: the clock is read for token freshness and HTTP-date
`Retry-After` values, and the sleeper is handed every `Retry-After` wait and
every tail reconnect delay. `Webhook` takes a clock as its last argument too.

## Generated code

`src/Model/` and `src/ObjectSerializer.php` are generated by
openapi-generator, and `src/Stream/`, `src/Events/Generated/`,
`src/Internal/Operations.php` and `src/Version.php` by this package's own
generator. They are never edited by hand. The models, the stream and event
classes and `InboundEvent` are API; `ObjectSerializer` and
`Lingara\Internal\` are not.

## The contract

Every official Lingara library keeps one contract,
[`conformance/CONTRACT.md`](https://github.com/Spinning-Cat-Studios/lingara_api_clients/blob/main/conformance/CONTRACT.md),
and passes its conformance cases on both built HTTP stacks. Where this
library meets a rule only on those stacks, the table above says so.

## Licence

MIT.
