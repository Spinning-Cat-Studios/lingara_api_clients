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

Thirteen methods, each the `operationId` in snake_case: `generate_vocabulary`,
`create_lesson_plan`, `get_lesson_plan`, `stream_lesson_plan`,
`send_tutor_message`, `get_usage`, `get_open_api_document`,
`get_async_api_document`, `list_api_versions`, `get_api_version`,
`list_events`, `stream_events` and `send_event`, plus the two event helpers
`events` and `tail_events` (see *Webhooks and events*). A path parameter is a positional
`String` (`client.get_lesson_plan(plan_id)`); a request body is keyword
arguments, and an unknown or missing keyword raises `ArgumentError` before any
request is sent.

A JSON method returns a `Lingara::Response`: `#value` is the generated model
(a `Hash` for `get_open_api_document`) and `#served_version` is the
`Lingara-Version` the server answered under, or `nil`.

`get_open_api_document`, `get_async_api_document`, `list_api_versions` and
`get_api_version` need no token, so `Lingara::Client.new` with no credentials
calls them. A client without credentials sends the other nine without
`Authorization`, and the server's `401` is the answer.

## Streams

The streaming methods take a block, or return a stream:

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

## Webhooks and events

Every door carries one envelope (`id`, `type`, `created_at`, `api_version`,
`subject`, `data`), and the library reads it into a `Lingara::Events::Event`:
one `Data` class per type (`Lingara::Events::LessonPlanReady`, whose `data`
is a `Lingara::LessonPlanReadyData`, and so on), or
`Lingara::Events::UnknownEvent`, whose `data` is the raw `Hash`.
`Lingara::Events.parse(json)` is the parser all three doors use.

**Verify the raw body first.** `Lingara::Events::Webhook.new(secret)` (or an
array of two secrets during a rotation) refuses anything but a
`lgr_whsec_…` secret with `ArgumentError`. `#verify(body, headers)` takes the
body exactly as received, a `String`, never a parsed object, and any `Hash`
of headers, read case-insensitively and by Rack's `HTTP_WEBHOOK_ID`
spelling, so a Rack `env` works as it is:

```ruby
webhook = Lingara::Events::Webhook.new(ENV.fetch("LINGARA_WEBHOOK_SECRET"))
event = webhook.verify(request.body.read, request.env)   # Rack, Sinatra and Rails alike
```

It returns the event, or raises `Lingara::Events::VerificationError`, whose
`#reason` is `:missing_header`, `:malformed_header`, `:timestamp_too_old`,
`:timestamp_too_new` (300 s either way), `:no_matching_signature` or
`:malformed_payload`. That error is deliberately **not** a `Lingara::Error`,
so a `rescue Lingara::Error` around your API calls never swallows a forged
webhook. `#verify_signature(body, headers)` checks the signature alone, for a
signed body that is not an event.

**Answer `2xx` fast and deduplicate by `event.id`**: delivery is at least once,
and the verifier stores nothing. **Acknowledge an `UnknownEvent`** (and log
it) rather than refusing it: the catalogue only grows, and a refused delivery
is retried for about a day.

**The feed.** `client.events(cursor:, start:, types:)` walks
`list_events`' pages and yields each event, then stops at the horizon: it
never waits. Its `#cursor` is where to resume, so keep it and pass it back
later. Without a cursor, `start: :latest` (the default) means "from now" and
`:oldest` means everything still kept. A cursor older than 30 days raises
`Lingara::ApiError` with `code` `"cursor_expired"`: start again with no
cursor, or with `start: :oldest`.

**The tail.** `client.tail_events(cursor:, start:, types:)` yields live
events and reconnects on its own from its `#cursor`, which is the same token
as the feed's, so `tail_events(cursor: feed.cursor)` follows a catch-up with
no gap. Leave the block to stop it. After `tail_max_failures:` (8)
consecutive failed reconnects, 91 s of backoff by default, it raises the last
failure; it never hides an outage forever. `stream_events` is the raw
one-connection stream beneath it.

**Sending.** `client.send_event(event, idempotency_key: nil)` takes a
`Lingara::Events::InboundEvent`, such as
`Lingara::Events::InboundEvent.world_context_changed(scene: "…", source_lang: "en", target_lang: "zh", level: 2, generate: true)`,
and returns the `InboundEventAccepted`. Without a key the library makes one
per call and repeats it on every retry. Supply your own when a game may
resend after a crash, since a generated key dies with the call; a key reused
for another event returns the **first** answer. Only
`reaction.plan_status == "generating"` promises a `lesson_plan.ready` or
`lesson_plan.failed`.

Event `data` is rendered at your client's pinned version, and this library's
models are those of `Lingara::GENERATED_FOR_VERSION`: pin your client to it.

## Embedding Lingara

A game or website that puts Lingara in front of its players vouches for each
player from its own server.

**Mint on your server, never on a player's device**, from a **metered**
client holding `embed:mint` (with an explicit `scopes:`, include it):

```ruby
minted = client.create_embed_token(player_ref: "guild-42/player-1001", scopes: ["embed:play"]).value
minted.subject              # "lgr_sub_…": store it beside the player
minted.token.expose_secret  # "lgr_et_…": hand it, with minted.expires_at, to the player kit
```

`player_ref` is your own reference for the player (at most 128 bytes). It is
sent as one path segment wherever it appears, so `/`, spaces and non-ASCII
text are safe. The result is a `Lingara::MintedToken`, and its `token` renders
`[REDACTED]` in `inspect`, `to_s` and `pp` like every other token here. Store
`subject`: it is how every event and webhook names that player. The token
lives 900 s and Lingara never refreshes it, so mint again when the player kit
asks. `expires_in` (whole seconds) is there for a device whose clock cannot be
trusted. An allowance client is refused with `403 embed_needs_metered`.

`client.delete_embed_player(player_ref)` deletes a player and revokes its
tokens. It returns a `Lingara::Response` whose `value` is `nil`. It is
idempotent, since an unknown player is a success too, and it keeps working
while embedding is switched off for your client.

`client.send_dialogue_turn(npc:, source_lang:, target_lang:, level:, line:, history:)`
streams one NPC reply (`embed:play`, from an embed token or a metered
client's own token) as `delta` and `notice` events, ending on `done`. The
window is yours to keep, and the schema bounds it: at most 12 `history`
entries, with `line` and each entry at most 500 characters. Send an NPC reply
back into `history` cut to its first 500 characters. A turn is **never
retried**, because each attempt spends the player's NPC cells and the payer's:
a `429` or `503` raises `Lingara::ApiError` at once with its `retry_after`,
and you decide whether to send the turn again. No retry helps
`403 embed_needs_metered` or `422 safety_input_flagged` (say something else).

`practice.completed` arrives typed as `Lingara::Events::PracticeCompleted` on
the feed, the tail and webhooks, and your client needs `events:read` and
`embed:play` to hear it. A game reports practice with
`Lingara::Events::InboundEvent.world_practice_completed(…)`, which needs
`events:write` and `embed:play`.

A player-side caller uses an embed token through a custom `token_source:`
whose `#token` returns `Lingara::AccessToken.new(lgr_et_token)`: no client
secret is involved. The library never sends `X-Lingara-Embed-Origin`, so a
token minted with `origin:` belongs to the browser widget, and a game mints
without one.

For higher-level helpers on your server, see the
[Lingara embed server kits](https://github.com/Spinning-Cat-Studios/lingara_embeddable_sdk)
(the `lingara-embed` gem).

## Errors

Every failure is a `Lingara::Error`, one of four:

| Class | When | Fields |
|---|---|---|
| `Lingara::ApiError` | a `/v1` refusal, or a stream's `error` event | `status`, `code`, `message`, `retry_after`, `plan_id`, `served_version` |
| `Lingara::OAuthError` | the token endpoint refused | `status`, `error`, `description`, `retry_after` |
| `Lingara::MaintenanceError` | the API is in maintenance | `body`, `retry_after` |
| `Lingara::TransportError` | no usable answer | `kind`: `:connect`, `:tls`, `:reset`, `:timeout`, `:stream_ended_early`, `:malformed_response`, `:malformed_event` |

A `429` or `503` with a `Retry-After` of at most `retry_after_cap:` (60 s) is
retried, up to `max_attempts:` (3) tries, except by `send_dialogue_turn`,
which sends once; a `401` fetches a fresh token and
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
| `tail_max_failures:` | `8`: consecutive failed reconnects before `tail_events` raises |
| `clock:`, `sleeper:` | `Time.now` and `sleep`. **Testing seams only**: the clock decides token freshness and HTTP-date waits, and the sleeper receives every `Retry-After` wait and every tail backoff |

The secret and every access token render as `[REDACTED]` in `inspect`,
`to_s` and `pp`; `#expose_secret` is the one accessor that reads them. A
client is safe to share between threads: concurrent callers share one token
exchange, which runs on its own thread, and a process forked mid-exchange
makes its own.

## The contract

This library keeps the contract every official Lingara library keeps:
[`conformance/CONTRACT.md`](../conformance/CONTRACT.md). Each connection is
opened for one request; the library pools none.
