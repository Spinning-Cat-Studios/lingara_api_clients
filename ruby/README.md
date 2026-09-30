# lingara (Ruby)

The official Ruby library for the [Lingara API](https://getlingara.com). It is
built on Ruby's standard library alone: `net/http`, `openssl` and `json`. The
gem has no runtime dependency, so `gem install lingara` fetches one gem and
nothing else.

This library is for your server. The Lingara API authenticates with a client
secret; the [authentication guide](https://getlingara.com/guides/authentication)
explains why credentials stay on a server.

## Install

```sh
gem install lingara
```

or, with Bundler, `gem "lingara"` in your `Gemfile`. **Ruby 3.3 or newer**: the
floor is the oldest Ruby branch still in upstream maintenance.

## Quick start

```ruby
require "lingara"

client = Lingara::Client.new(
  client_id: ENV.fetch("LINGARA_CLIENT_ID"),
  client_secret: ENV.fetch("LINGARA_CLIENT_SECRET"),
  # version: "2026-09-knowing-tenpounder",   # optional pin
)

client.generate_vocabulary(level: 2, source_lang: "en", target_lang: "zh", count: 8) do |event|
  case event
  in Lingara::GenerateVocabularyEventItem => item then puts "#{item.data.word}: #{item.data.translation}"
  else nil
  end
end

usage = client.get_usage          # Lingara::Response
p usage.value.allowance, usage.served_version
```

Nine methods, each the `operationId` in snake_case: `generate_vocabulary`,
`create_lesson_plan`, `get_lesson_plan`, `stream_lesson_plan`,
`send_tutor_message`, `get_usage`, `get_open_api_document`,
`list_api_versions` and `get_api_version`. A path parameter is a positional
`String` (`client.get_lesson_plan(plan_id)`); a request body is keyword
arguments, and an unknown or missing keyword raises `ArgumentError` before any
request is sent.

A JSON method returns a `Lingara::Response`: `#value` is the generated model
(a `Hash` for `get_open_api_document`) and `#served_version` is the
`Lingara-Version` the server answered under, or `nil`.

`get_open_api_document`, `list_api_versions` and `get_api_version` need no
token, so `Lingara::Client.new` with no credentials calls them. A client
without credentials sends the other six without `Authorization`, and the
server's `401` is the answer.

## Streams

The four streaming methods take a block, or return a stream:

- **With a block**, the call sends the request, yields each event and returns
  a `Lingara::StreamResult` (`#served_version`). `break`, `return` or `throw`
  out of the block closes the connection.
- **Without a block**, the call returns a `Lingara::EventStream`, which is
  `Enumerable`. **The request is sent lazily**, on the first `each` or
  `next`, so a refusal surfaces there rather than at the call, and a stream
  you never iterate holds no connection. It is **single-use**: a second
  `each` raises `IOError`. After external iteration with `next`, call
  `#close` if you stop before the end; the block form needs no `close`.

```ruby
stream = client.stream_lesson_plan(plan_id)
begin
  first = stream.next
ensure
  stream.close
end
```

Each event is a generated branch class (`Lingara::GenerateVocabularyEventItem`
has `#event` and `#data`), and `in Lingara::GenerateVocabularyEvent` matches
any of that stream's branches. Which events end a stream is the spec's: the
stream ends after `result` or `pending`, which are yielded, and after `done`,
which is not. An `error` event is never yielded: it raises
`Lingara::ApiError` with `status` 200 and, for a lesson plan, `plan_id`.

A stream fails with `Lingara::TransportError` (`kind: :timeout`) after
`stream_idle_timeout:` seconds (120 by default) in which no byte arrives
while the library is waiting for your next event. Time you spend holding an
event never counts.

## Errors

Every failure is a `Lingara::Error`, one of four:

| Class | When | Fields |
|---|---|---|
| `Lingara::ApiError` | a `/v1` refusal, or a stream's `error` event | `status`, `code`, `message`, `retry_after`, `plan_id`, `served_version` |
| `Lingara::OAuthError` | the token endpoint refused | `status`, `error`, `description`, `retry_after` |
| `Lingara::MaintenanceError` | the API is in maintenance | `body`, `retry_after` |
| `Lingara::TransportError` | no usable answer | `kind`: `:connect`, `:tls`, `:reset`, `:timeout`, `:stream_ended_early`, `:malformed_response`, `:malformed_event` |

A `429` or `503` with a `Retry-After` of at most `retry_after_cap:` (60 s) is
retried, up to `max_attempts:` (3) tries; a `401` fetches a fresh token and
retries once. A stream that has yielded an event is never replayed. Your own
interrupt (`Thread#raise`, `Thread#kill`, `Timeout.timeout`) is never wrapped
and never retried.

## Options

Every option is a keyword of `Lingara::Client.new`:

| Keyword | Default |
|---|---|
| `client_id:`, `client_secret:` | none: a credential-free client |
| `auth:` | `:basic` (`client_secret_basic`); `:post` for `client_secret_post` |
| `scopes:` | none: every scope the client is allowed |
| `token_source:` | a client-credentials source; any object with `#token` (returning a `Lingara::AccessToken`) and `#invalidate(token)` replaces it |
| `base_url:`, `token_url:` | `https://api.getlingara.com`, and its `/oauth/token` |
| `version:` | no `Lingara-Version` header |
| `on_deprecation:` | one warning per version id through `logger:`; a callable receiving a `Lingara::DeprecationNotice` replaces it |
| `logger:` | `Kernel#warn` (so `ruby -W0` silences it); any object with `warn` and `debug`, such as `Rails.logger` |
| `max_attempts:`, `retry_after_cap:` | `3`, `60` |
| `stream_idle_timeout:`, `token_request_timeout:` | `120`, `30` (seconds) |
| `user_agent_suffix:` | none; appended after the library's own product token |
| `net_http_options:` | `{}`: forwarded to `Net::HTTP.start` (`ca_file:`, `open_timeout:` …). The library always sets `max_retries` to `0`, and a stream's `read_timeout` is the idle timeout |
| `clock:`, `sleeper:` | `Time.now` and `sleep`. **Testing seams only**: the clock decides token freshness and HTTP-date waits, and the sleeper receives every `Retry-After` wait |

The secret and every access token render as `[REDACTED]` in `inspect`,
`to_s` and `pp`; `#expose_secret` is the one accessor that reads them. A
client is safe to share between threads: concurrent callers share one token
exchange, which runs on its own thread, and a process forked mid-exchange
makes its own.

## The contract

This library keeps the contract every official Lingara library keeps:
[`conformance/CONTRACT.md`](../conformance/CONTRACT.md). Each connection is
opened for one request; the library pools none.
