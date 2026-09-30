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

Omit both credentials for a client that calls only the three public
operations: `getOpenApiDocument`, `listApiVersions` and `getApiVersion`.

Every option is a named argument of `new Client(...)`: `authMethod`,
`scopes`, `tokenSource`, `tokenCache`, `baseUrl`, `tokenUrl`, `version`,
`onDeprecation`, `logger`, `maxAttempts`, `retryAfterCap`,
`streamIdleTimeout`, `tokenRequestTimeout`, `userAgentSuffix`, `clock`,
`sleeper` and `http`. Durations are `float` seconds.

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
`Retry-After` values, and the sleeper is handed every `Retry-After` wait.

## Generated code

`src/Model/` and `src/ObjectSerializer.php` are generated by
openapi-generator, and `src/Stream/`, `src/Internal/Operations.php` and
`src/Version.php` by this package's own generator. They are generated
support, not API, and are never edited by hand. `Lingara\Internal\` is not
API either.

## The contract

Every official Lingara library keeps one contract,
[`conformance/CONTRACT.md`](https://github.com/Spinning-Cat-Studios/lingara_api_clients/blob/main/conformance/CONTRACT.md),
and passes its conformance cases on both built HTTP stacks. Where this
library meets a rule only on those stacks, the table above says so.

## Licence

MIT.
