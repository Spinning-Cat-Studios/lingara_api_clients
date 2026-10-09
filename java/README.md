# lingara-java

The official Java library for the [Lingara API](https://getlingara.com): vocabulary lists, lesson plans and tutor conversations in the languages Lingara teaches.

It keeps the [client contract](../conformance/CONTRACT.md) every Lingara library keeps: token caching and refresh, one retry on a 401, `Retry-After` handling, streams you can close, and one family of errors.

- Java 17 or newer, server-side JVM.
- One runtime dependency: Jackson databind 2.22 or newer 2.x (below).
- HTTP is the JDK's own `java.net.http.HttpClient`.

## Install

Maven:

```xml
<dependency>
  <groupId>com.getlingara</groupId>
  <artifactId>lingara-java</artifactId>
  <version>VERSION</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.getlingara:lingara-java:VERSION")
```

The library is a named module, `com.getlingara.client`, and works on the class path too.

## Quick start

```java
import com.getlingara.client.EventStream;
import com.getlingara.client.LingaraClient;
import com.getlingara.client.model.GenerateVocabularyEvent;
import com.getlingara.client.model.Usage;
import com.getlingara.client.model.VocabRequest;

LingaraClient client = LingaraClient.builder()
    .clientCredentials(System.getenv("LINGARA_CLIENT_ID"), System.getenv("LINGARA_CLIENT_SECRET"))
    .build();

VocabRequest request = new VocabRequest().level(2).sourceLang("en").targetLang("zh").count(8);
try (EventStream<GenerateVocabularyEvent> stream = client.generateVocabulary(request)) {
  for (GenerateVocabularyEvent event : stream) {
    if (event instanceof GenerateVocabularyEvent.Item item) {
      System.out.println(item.data().getWord() + " " + item.data().getTranslation());
    }
  }
}
Usage usage = client.getUsage().body();
```

A client is safe to share between threads and needs no `close()`. Build one per set of credentials and keep it: it caches its access token.

Four operations need no token: `getOpenApiDocument`, `getAsyncApiDocument`, `listApiVersions` and `getApiVersion`. A client built with no credentials can call them.

## Calls block

Every method blocks. From Java 21 a virtual thread makes that cheap. On Java 17, submit calls to an executor. There is no `CompletableFuture` variant. Kotlin coroutine users have `lingara-kotlin`.

A stream method sends its request at once and returns an `EventStream` when the response headers are in. So a refusal (a 401 after its one retry, a 429, any other error status) is thrown by the call, and a failure during the stream is thrown by the iterator's `hasNext()`.

## Streams

`EventStream<E>` is `Iterable<E>` and `AutoCloseable`. Iterate it once, inside `try`-with-resources. `stream()` gives a `java.util.stream.Stream<E>` whose `close()` closes the connection.

Each stream's events are a sealed interface of records, such as `GenerateVocabularyEvent.Started` and `GenerateVocabularyEvent.Item`, each holding its payload as `data()`. Take them apart with `instanceof` patterns, or from Java 21 with a pattern `switch`:

```java
switch (event) {
  case GenerateVocabularyEvent.Started started -> System.out.println(started.data().getMeta());
  case GenerateVocabularyEvent.Item item -> System.out.println(item.data().getWord());
  default -> { } // a newer release may add events
}
```

A `switch` with no `default` stops compiling when a later release adds an event, and code compiled against an older release throws `MatchException` if the new event reaches it. Keep a `default` arm if you would rather it stayed quiet.

An `error` event is never yielded: `hasNext()` throws it as an `ApiException` with status 200, whose `planId()` lets you rejoin a lesson plan with `streamLessonPlan`. A stream fails with `TransportException` kind `TIMEOUT` after 120 seconds with no byte from the server (`streamIdleTimeout`). Time you spend holding an event does not count. A stream never reconnects by itself.

## Errors

Every failure is a `LingaraException`, which is unchecked and sealed:

| Class | When |
|---|---|
| `ApiException` | the API refused the call, or a stream sent an `error` event |
| `OAuthException` | the token endpoint refused the credentials or the scopes |
| `MaintenanceException` | the API is under maintenance |
| `TransportException` | no usable answer: `kind()` says why (`CONNECT`, `TLS`, `RESET`, `TIMEOUT`, `STREAM_ENDED_EARLY`, `MALFORMED_RESPONSE`, `MALFORMED_EVENT`) |

A `429` or `503` with a `Retry-After` of at most 60 seconds is retried for you, up to three attempts in total (`maxAttempts`, `retryAfterCap`). A longer wait is thrown at once, with `retryAfter()` set, so you can schedule it yourself.

## Cancellation

Interrupt the calling thread, or `close()` a stream. An interrupted call throws the JDK's `java.util.concurrent.CancellationException` with the thread's interrupt flag restored. It is not a `LingaraException`, so `catch (LingaraException e)` never swallows it. A `close()` from another thread does the same to a blocked `hasNext()`. A `close()` on the iterating thread, such as a `break` out of `try`-with-resources, ends iteration quietly.

## Options

Every option is a builder method: `clientCredentials`, `clientSecretPost`, `scopes`, `tokenSource`, `baseUrl`, `tokenUrl`, `version`, `onDeprecation`, `maxAttempts`, `retryAfterCap`, `streamIdleTimeout`, `tokenRequestTimeout`, `userAgentSuffix`, `httpClient`, `requestTimeout`, `tailMaxFailures`, and the two testing seams, `clock` and `sleeper`.

- **`version(id)`** pins every request to one API version. Without it the server applies the version your OAuth client is pinned to. `servedVersion()` on every response and stream says which version answered.
- **`onDeprecation(hook)`** is called once per response under a deprecated version. Without a hook the library logs one warning per version id through `System.Logger` (logger `com.getlingara.client`).
- **`httpClient(client)`** sets the HTTP client, for proxies, TLS and executors. The stream idle timeout and the token request timeout are enforced by the library itself, so both still hold on your own client. A caller-supplied client's connect timeout is its own.
- **`requestTimeout(duration)`** bounds the wait for each response's headers. There is none by default. A JSON call against a server that accepts and then stalls waits until you interrupt the thread.
- **`clock` and `sleeper`** exist for tests: they let a test move time and skip `Retry-After` waits. Leave them alone in production.

The library starts daemon threads named `lingara-java-*` on first use, for token exchanges and stream timeouts. They never keep a JVM alive.

## Webhooks and events

Everything here lives in `com.getlingara.client.events`.

**Verify the raw body first.** `Webhook.of(secret)` takes your endpoint's `lgr_whsec_…` secret (two during a rotation) and `verify(byte[] body, Map<String, List<String>> headers)` checks the Standard Webhooks signature and the 300 s timestamp window before it parses anything. Hand it the bytes exactly as received: a servlet's `request.getInputStream().readAllBytes()` or Spring's `@RequestBody byte[] body`, never a re-serialised object. A failure is a `WebhookVerificationException` with a `reason()`; it is deliberately not a `LingaraException`, so a catch-all around API calls never swallows a forged webhook. `verifySignature` checks the signature alone, for a signed body that is not an event.

```java
Webhook webhook = Webhook.of(System.getenv("LINGARA_WEBHOOK_SECRET"));
Event event = webhook.verify(body, headers);
```

**Answer `2xx` fast and deduplicate by `event.id()`.** Delivery is at least once and unordered, and the library stores nothing. Do slow work after you answer.

**`UnknownEvent` is a type newer than this library.** Acknowledge it and log it: a receiver that answers an error gets the same event retried for about a day. `Event` is a sealed interface, so a `switch` or `instanceof` chain over its records covers every type this release knows.

**The feed.** `client.events(EventsRequest.of().cursor(saved))` iterates every event since `saved`, page by page, and stops when it has caught up; it never sleeps or polls. Save `feed.cursor()` and call it again later. Without a cursor it begins at `start("latest")` (from now) or `start("oldest")` (everything still kept). A cursor older than the 30-day window is `ApiException` with `code()` `cursor_expired`: start again without one, or with `start("oldest")`. `listEvents` is the single-page operation underneath.

**The tail.** `client.tailEvents(…)` is an `EventStream<Event>` that reconnects from its own `cursor()` after every ending, sending `Last-Event-ID`. A connection that fails is retried after 1, 2, 4, 8, 16, 30 and 30 s; the eighth failure in a row is thrown, about 91 s in. Raise `tailMaxFailures` to ride out longer outages, or catch the error and restart from `cursor()`. The feed's cursor and the tail's are the same token, so a game can catch up with `events` and then hand over to `tailEvents`. `streamEvents` is the one-connection operation underneath.

**Sending events.** `client.sendEvent(InboundEvent.worldContextChanged(scene))` sends with a generated `Idempotency-Key`, the same on every retry of that call. Pass `new SendEventOptions(key)` when your game may resend after a crash: a resend with the same key gets the first answer back and is not billed again. A key reused for a different event also gets the first answer, so that event is lost. Only `reaction.planStatus` `GENERATING` promises a `lesson_plan.ready` or `lesson_plan.failed` event; a `PARTIAL` or `COMPLETE` plan was served from the library and can be read at once.

**Pin your client** to `LingaraClient.GENERATED_FOR_VERSION`. Event data is rendered at your OAuth client's pinned version, and the records are this release's models.

## Embedding Lingara

A game or website can vouch for its own players: your server mints each player a short-lived embed token, and the player's device uses it.

```java
MintedToken minted =
    client.createEmbedToken(new EmbedTokenRequest().playerRef("player-1001")).body();
// store minted.subject(); hand minted.token().exposeSecret() (lgr_et_…) to the device
```

- **Mint on your server, never on the player's device**, from a **metered** client holding `embed:mint` (a client built with explicit `scopes` must list it). Otherwise the answer is a `403` `insufficient_scope` or `embed_needs_metered`, thrown as an `ApiException`. **Store `subject()`** beside your player: it is the player's stable `lgr_sub_`, and how every event names them.
- `MintedToken.token()` renders as `[REDACTED]` like every token here, and so does the `MintedToken` itself; `exposeSecret()` reads it. The token lives 900 s and Lingara never refreshes it, so mint again when the player kit asks. `expiresIn()` (a `Duration`) is there for a device whose clock cannot be trusted; `expiresAt()` for one whose clock can.
- `deleteEmbedPlayer(playerRef)` deletes a player and revokes their tokens. An unknown player is still a success, so it is idempotent, and it keeps working while embedding is switched off for your client. The answer has no body: the `ApiResponse<Void>` carries only `servedVersion()`.
- `sendDialogueTurn` streams an NPC's reply, `Delta` by `Delta`. The window is yours: at most 12 `history` entries, `line` and each entry at most 500 characters, and no total cap. Send each NPC reply back cut to its first 500 characters. A turn is **never retried**: each attempt spends the player's NPC cells and your metered cells, so a `429` or `503` is thrown at once as an `ApiException` with its `retryAfter()`, and you decide whether to send it again. No retry helps `403 embed_needs_metered`, or `422 safety_input_flagged`, which means say something else.
- `PracticeCompleted` arrives through the webhook, the feed and the tail when your client holds `events:read` and `embed:play`; its `subject()` names the player. `InboundEvent.worldPracticeCompleted(data)` sends one, with `events:write` and `embed:play`.
- **A player-side caller** supplies its embed token through a custom `TokenSource` (`tokenSource(…)`, wrapping it in `new AccessToken(…)`); no client secret is involved there. The library never sends `X-Lingara-Embed-Origin`, so a token minted with an `origin` belongs to the browser widget: a game mints without one.

The [embed kits](https://github.com/Spinning-Cat-Studios/lingara_embeddable_sdk) build on these calls: server kits for higher-level minting and webhook helpers, and player kits for the engines.

## Jackson

The generated models carry Jackson annotations, so Jackson databind is an `api` dependency and is not shaded: a shaded copy would double your Jackson and hide its security fixes. The library is built against Jackson databind 2.22. If your build pins an older 2.x, raise it to 2.22 or newer. Jackson 3 (`tools.jackson`) is a separate library and is not used.

Timestamps and ids are the server's strings. Parse a `date-time` with `OffsetDateTime.parse` when you need one. Unsigned 64-bit counters are `BigInteger`, 32-bit ones `Long`.

## Security

The client secret and access tokens never appear in any `toString()`, exception message or log line. They render as `[REDACTED]`. `ClientSecret.exposeSecret()` and `AccessToken.exposeSecret()` are the only ways to read them. See [SECURITY.md](../SECURITY.md).
