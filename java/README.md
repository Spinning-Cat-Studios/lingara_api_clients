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

Three operations need no token: `getOpenApiDocument`, `listApiVersions` and `getApiVersion`. A client built with no credentials can call them.

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

Every option is a builder method: `clientCredentials`, `clientSecretPost`, `scopes`, `tokenSource`, `baseUrl`, `tokenUrl`, `version`, `onDeprecation`, `maxAttempts`, `retryAfterCap`, `streamIdleTimeout`, `tokenRequestTimeout`, `userAgentSuffix`, `httpClient`, `requestTimeout`, and the two testing seams, `clock` and `sleeper`.

- **`version(id)`** pins every request to one API version. Without it the server applies the version your OAuth client is pinned to. `servedVersion()` on every response and stream says which version answered.
- **`onDeprecation(hook)`** is called once per response under a deprecated version. Without a hook the library logs one warning per version id through `System.Logger` (logger `com.getlingara.client`).
- **`httpClient(client)`** sets the HTTP client, for proxies, TLS and executors. The stream idle timeout and the token request timeout are enforced by the library itself, so both still hold on your own client. A caller-supplied client's connect timeout is its own.
- **`requestTimeout(duration)`** bounds the wait for each response's headers. There is none by default. A JSON call against a server that accepts and then stalls waits until you interrupt the thread.
- **`clock` and `sleeper`** exist for tests: they let a test move time and skip `Retry-After` waits. Leave them alone in production.

The library starts daemon threads named `lingara-java-*` on first use, for token exchanges and stream timeouts. They never keep a JVM alive.

## Jackson

The generated models carry Jackson annotations, so Jackson databind is an `api` dependency and is not shaded: a shaded copy would double your Jackson and hide its security fixes. The library is built against Jackson databind 2.22. If your build pins an older 2.x, raise it to 2.22 or newer. Jackson 3 (`tools.jackson`) is a separate library and is not used.

Timestamps and ids are the server's strings. Parse a `date-time` with `OffsetDateTime.parse` when you need one. Unsigned 64-bit counters are `BigInteger`, 32-bit ones `Long`.

## Security

The client secret and access tokens never appear in any `toString()`, exception message or log line. They render as `[REDACTED]`. `ClientSecret.exposeSecret()` and `AccessToken.exposeSecret()` are the only ways to read them. See [SECURITY.md](../SECURITY.md).
