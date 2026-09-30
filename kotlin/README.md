# lingara-kotlin

The official Kotlin library for the [Lingara API](https://getlingara.com): vocabulary lists, lesson plans and tutor conversations in the languages Lingara teaches.

It keeps the [client contract](../conformance/CONTRACT.md) every Lingara library keeps: token caching and refresh, one retry on a 401, `Retry-After` handling, streams you can cancel, and one family of errors.

- Kotlin/JVM on Java 17 or newer, server-side. Your compiler needs to be Kotlin 2.2 or newer: the library's `kotlinx-serialization` dependency carries 2.3 metadata, and a compiler reads one metadata version ahead.
- Two runtime dependencies: `kotlinx-serialization-json` and `kotlinx-coroutines-core`. No Jackson, Ktor or OkHttp.
- HTTP is the JDK's own `java.net.http.HttpClient`, the same transport as `lingara-java`.

## Install

Gradle:

```kotlin
implementation("com.getlingara:lingara-kotlin:VERSION")
```

Maven:

```xml
<dependency>
  <groupId>com.getlingara</groupId>
  <artifactId>lingara-kotlin</artifactId>
  <version>VERSION</version>
</dependency>
```

The packages are `com.getlingara.kotlin` and `com.getlingara.kotlin.model`, apart from `lingara-java`'s, so both can share a class path.

## Quick start

```kotlin
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.model.GenerateVocabularyEvent
import com.getlingara.kotlin.model.VocabRequest

val client = LingaraClient {
    clientCredentials(System.getenv("LINGARA_CLIENT_ID"), System.getenv("LINGARA_CLIENT_SECRET"))
}

client.generateVocabulary(VocabRequest(level = 2, sourceLang = "en", targetLang = "zh", count = 8)).use { stream ->
    stream.collect { event ->
        when (event) {
            is GenerateVocabularyEvent.Item -> println("${event.data.word} ${event.data.translation}")
            is GenerateVocabularyEvent.Started -> Unit
        }
    }
}
val usage = client.getUsage().body
```

Every method is a `suspend fun`. A client is safe to share between coroutines and needs no `close()`. Build one per set of credentials and keep it: it caches its access token.

Three operations need no token: `getOpenApiDocument`, `listApiVersions` and `getApiVersion`. A client built with no credentials, `LingaraClient {}`, can call them.

## Streams are collected once

A stream method sends its request at once and returns an `EventStream` when the response headers are in. So a refusal (a 401 after its one retry, a 429, any other error status) is thrown by the call, and a failure during the stream is thrown into the collector.

`EventStream<E>` is a `Flow<E>` and a `Closeable`. It is **not** a cold flow: the request has already been sent, so a second `collect` throws `IllegalStateException` rather than silently sending the request, and spending your allowance, again. `map`, `takeWhile`, `first` and the other operators work as usual. Collection closes the connection however it ends. Wrap the stream in `use {}` so a stream you never collect is closed too.

Each stream's events are a sealed interface of data classes, such as `GenerateVocabularyEvent.Started` and `GenerateVocabularyEvent.Item`, each holding its payload as `data`. A `when` over them without an `else` stops compiling when a later release adds an event, and code compiled against an older release throws `NoWhenBranchMatchedException` if the new event reaches it. Add an `else -> Unit` branch if you would rather it stayed quiet.

An `error` event is never emitted: it is thrown as an `ApiException` with status 200, whose `planId` lets you rejoin a lesson plan with `streamLessonPlan`. A stream fails with `TransportException` kind `TIMEOUT` after 120 seconds with no byte from the server (`streamIdleTimeout`). Time your collector spends handling an event does not count. A stream never reconnects by itself.

## Errors

Every failure is a `LingaraException`, a sealed class, so a `when` over it is exhaustive:

| Class | When |
|---|---|
| `ApiException` | the API refused the call, or a stream sent an `error` event |
| `OAuthException` | the token endpoint refused the credentials or the scopes |
| `MaintenanceException` | the API is under maintenance |
| `TransportException` | no usable answer: `kind` says why (`CONNECT`, `TLS`, `RESET`, `TIMEOUT`, `STREAM_ENDED_EARLY`, `MALFORMED_RESPONSE`, `MALFORMED_EVENT`) |

A `429` or `503` with a `Retry-After` of at most 60 seconds is retried for you, up to three attempts in total (`maxAttempts`, `retryAfterCap`). A longer wait is thrown at once, with `retryAfter` set, so you can schedule it yourself.

Decoding is `kotlinx.serialization`'s, which is stricter than Jackson in two ways: a response missing a required field is `MALFORMED_RESPONSE`, and an enum value this release does not know fails the same way until you upgrade. A new field is ignored.

## Cancellation

Cancellation is the coroutine's own: cancel the calling `Job`, wrap the call in `withTimeout`, or stop collecting with `first` or `take`. The exchange is aborted and you get your own `CancellationException`, never a `LingaraException`, so `catch (e: LingaraException)` never swallows it. There is no JSON-call timeout option, because `withTimeout` already is one.

A `close()` from another coroutine ends a collection normally, with no further event.

## Options

Every option is a property or function of the `LingaraClient { … }` builder: `clientCredentials`, `clientSecretPost()`, `scopes`, `tokenSource`, `baseUrl`, `tokenUrl`, `version`, `onDeprecation`, `maxAttempts`, `retryAfterCap`, `streamIdleTimeout`, `tokenRequestTimeout`, `userAgentSuffix`, `httpClient`, and the two testing seams, `clock` and `sleeper`.

- **`version`** pins every request to one API version. Without it the server applies the version your OAuth client is pinned to. `servedVersion` on every response and stream says which version answered.
- **`onDeprecation { … }`** is called once per response under a deprecated version. Without a hook the library logs one warning per version id through `System.Logger` (logger `com.getlingara.kotlin`), which reaches SLF4J or Log4j through their `System.LoggerFinder` bridges.
- **`httpClient`** sets the HTTP client, for proxies, TLS and executors. The stream idle timeout and the token request timeout are enforced by the library itself, so both still hold on your own client. A caller-supplied client's connect timeout is its own.
- **`clock` and `sleeper`** exist for tests: they let a test move time and skip `Retry-After` waits. Leave them alone in production. The two timeouts the library owns run on real time even under `runTest`, so a test that needs them sets them short.

Timestamps and ids are the server's strings. Parse a `date-time` with `Instant.parse` when you need one. Unsigned 64-bit counters are `ULong`, 32-bit ones `Long`.

## Security

The client secret and access tokens never appear in any `toString()`, exception message or log line. They render as `[REDACTED]`. `ClientSecret.exposeSecret()` and `AccessToken.exposeSecret()` are the only ways to read them. See [SECURITY.md](../SECURITY.md).
