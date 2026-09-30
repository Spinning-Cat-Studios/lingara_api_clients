# lingara (Go)

The official Go library for the [Lingara API](https://getlingara.com). It is
built on the standard library alone: `net/http`, `encoding/json`, `context`
and Go 1.23's range-over-func iterators. The module's `require` block is
empty, so `go get` fetches this module and nothing else.

This library is for your server. The Lingara API authenticates with a client
secret; the [authentication guide](https://getlingara.com/guides/authentication)
explains why credentials stay on a server.

## Install

```sh
go get github.com/Spinning-Cat-Studios/lingara_api_clients/go
```

**Go 1.23 or newer.** The module lives in the `go/` directory of a repository
that holds seven libraries, so its import path ends in `/go`. Import it under
the name `lingara`:

```go
import lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
```

## Quick start

```go
client, err := lingara.New(
	lingara.WithClientCredentials(os.Getenv("LINGARA_CLIENT_ID"), os.Getenv("LINGARA_CLIENT_SECRET")),
	// lingara.WithVersion("2026-09-knowing-tenpounder"), // optional pin
)
if err != nil {
	return err
}

count := uint8(8)
s, err := client.GenerateVocabulary(ctx, lingara.VocabRequest{Level: 2, SourceLang: "en", TargetLang: "zh", Count: &count})
if err != nil {
	return err // a refusal: auth and retries are already done
}
defer s.Close()
for ev, err := range s.Events() {
	if err != nil {
		return err // the stream's last pair: a lingara error, or ctx.Err()
	}
	if item, ok := ev.(lingara.GenerateVocabularyEventItem); ok {
		fmt.Println(item.Data.Word, item.Data.Translation)
	}
}

usage, err := client.GetUsage(ctx) // *lingara.Result[lingara.Usage]
if err != nil {
	return err
}
fmt.Println(usage.Value.Allowance, usage.ServedVersion)
```

Nine methods, each the `operationId` with its first letter upper-cased and
Go's initialisms applied. Every one takes a `context.Context` first.

- `GenerateVocabulary`, `CreateLessonPlan`, `StreamLessonPlan` and
  `SendTutorMessage` send their request at once and return a
  `*lingara.Stream[E]` when the response headers are in. So a refusal is the
  call's error, and an in-stream failure is the last pair of `Events()`.
  `E` is a sealed interface; type-switch on its branches.
- `Events()` can be ranged **once**. A second `range` yields one
  `lingara.ErrStreamConsumed`, because ranging again would otherwise re-send
  the request and spend your allowance twice. `defer s.Close()`: a stream that
  is never ranged holds its connection until then.
- An `error` event is never yielded as an event. It is the last pair's
  `*lingara.APIError`, with `Status` 200. `done` ends the stream unyielded,
  and `result` and `pending` are yielded, then the stream ends.
- `GetLessonPlan`, `GetUsage`, `GetOpenAPIDocument`, `ListAPIVersions` and
  `GetAPIVersion` return a `*lingara.Result[T]`: `Value` and `ServedVersion`.
  The last three need no credentials, so `lingara.New()` is enough for them.

**Cancellation is the context.** Cancel it and the call returns `ctx.Err()`,
never a lingara error, so `errors.Is(err, context.Canceled)` holds. A stream
closes its connection. Breaking out of the `range` closes it too, and `Close`
may be called from another goroutine.

## Errors

Every error a call returns, cancellation aside, is one of four types, and all
four implement `lingara.Error`:

- `*APIError`: a refusal from `/v1`, or a stream's `error` event.
- `*OAuthError`: the token endpoint refused. Its RFC 6749 `error` field is
  `ErrorCode`, since a Go type cannot have both a field and a method named
  `Error`.
- `*MaintenanceError`: a plain-text 503.
- `*TransportError`: no usable answer. Its `Kind` is `Connect`, `TLS`,
  `Reset`, `Timeout`, `StreamEndedEarly`, `MalformedResponse` or
  `MalformedEvent`.

Use `errors.As`. A `Retry-After` longer than `WithRetryAfterCap` is returned in
the error's `RetryAfter` rather than slept.

The client secret and every access token render as `[REDACTED]` in every
`fmt` verb, in `log/slog` and in JSON, and so do the client, the token source
and every error. `ExposeSecret()` is the one way to read either.

## Options

| Option | Default |
|---|---|
| `WithClientCredentials(id, secret)` | none: a credential-free client |
| `WithClientSecretPost()` | `client_secret_basic` |
| `WithScopes(...)` | none: every scope the client is allowed |
| `WithTokenSource(ts)` | a `ClientCredentials` from the options above; not with `WithClientCredentials` |
| `WithBaseURL` / `WithTokenURL` | `https://api.getlingara.com` / `…/oauth/token` |
| `WithVersion(id)` | none: your client's pinned version |
| `WithDeprecationHook(fn)` | one `slog.Warn` per deprecated version id |
| `WithMaxAttempts(n)` | `3`; `1` turns retries off |
| `WithRetryAfterCap(d)` | 60 s |
| `WithStreamIdleTimeout(d)` | 120 s, counted only while a read is waiting |
| `WithTokenRequestTimeout(d)` | 30 s per attempt of the token exchange |
| `WithUserAgentSuffix(s)` | none: appended after the library's own token |
| `WithHTTPClient(c)` | a fresh `http.Client` with no `Timeout` |
| `WithClock`, `WithSleeper` | the real ones |

`WithClock` and `WithSleeper` are **testing seams**: they let a test control
refresh timing and `Retry-After` sleeps without waiting in real time. Leave
them alone in production.

**Your own `http.Client`.** Use it for proxies, TLS and pools. A `Timeout` on
it caps every call, streams included, so a long stream is cut at that
deadline. The stream idle timeout is the library's own, so it holds either
way. The library never touches `http.DefaultClient` or
`http.DefaultTransport`.

**A cancelled token exchange.** Concurrent calls share one token exchange.
Cancelling your call abandons only your wait: the exchange finishes and caches
its token for the next call, which is why it is bounded by
`WithTokenRequestTimeout` instead of by your context.

**The deprecation hook** runs inside the call, once per response under a
deprecated version. A hook that panics is recovered and logged at debug, and
the call continues.

**The version your models were generated from** is
`lingara.GeneratedForVersion`. Leave `WithVersion` unset and your OAuth
client's server-side pin decides what is served; the library never sends a
default. When a response is served from another version, the library logs one
`slog.Warn` per served id, since the response shapes may differ from the
models. Pin the OAuth client (or use `WithVersion`) to the generated-for
version, or upgrade the library.

## The contract

This library keeps the contract every official Lingara library keeps, and
runs its conformance suite in CI:
[`conformance/CONTRACT.md`](https://github.com/Spinning-Cat-Studios/lingara_api_clients/blob/main/conformance/CONTRACT.md).

## Licence

MIT.
