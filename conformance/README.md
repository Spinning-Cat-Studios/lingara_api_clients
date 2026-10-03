# Conformance

Every Lingara library keeps one contract, [`CONTRACT.md`](CONTRACT.md). This
directory makes it executable:

- [`cases/`](cases/) — one YAML file per case, grouped `op` (one per
  operation) and `k1`…`k6` and `k5a` (one group per contract section);
- [`vectors/`](vectors/) — data every library's unit suite reads directly,
  because it makes no request (see *Vectors* below);
- [`server/`](server/) — a Rust mock that replays each case's exchanges,
  checks every request it was sent, and drives a language's harness;
- each language directory's harness, which runs every case through that
  library's public API.

```sh
make check-conformance-coverage   # every operation, K1–K6 and K5a has a case; every case parses
make conformance                  # every landed language's harness, every case
make conformance-rust             # one language
```

## Adding a case

Write `cases/<group>/<slug>.yaml`; its `id` MUST be `<group>.<slug>`. The
schema is closed (unknown keys are refused), and
`make check-conformance-coverage` parses every file. Adding a case needs no
ADR; adding a key to the schema does. Case credentials are the obviously fake
`lgr_cid_conformance…` / `lgr_cs_conformance…` values.

```yaml
id: <group>.<slug>
title: <one sentence>
behaviours: [K1, K5]             # ≥ 1, of K1–K6 and K5a
client:                          # how the harness builds the client
  credentials: { client_id: <str>, client_secret: <str>, auth: basic | post }   # omit = no credentials
  scopes: [<scope>…]
  version: <version id>
  retries: { max_attempts: <n>, retry_after_cap_s: <n> }
  deprecation_hook: record       # the harness records hook calls
  user_agent_suffix: <str>
  stream_idle_timeout_ms: <n>
  base_url: unreachable          # the harness points the client at a closed port
steps:                           # run in order
  - call: { operation: <operationId>, params: {…}, body: {…}, parallel: <n>, cancel_after_events: <n>, idempotency_key: <str> }
    expect:
      outcome: completed | error | cancelled
      status: <int>
      body: {…}
      events: [ { event: <name>, data: {…} } … ]   # yielded events, in order
      error: { variant: ApiError | OAuthError | MaintenanceError | TransportError, fields: {…} }
      served_version: <str>
      sleeps_s: [<int>…]
      hook_calls: [ { version, deprecated_at, sunset_at, link } … ]
      redacted: [<string>…]
      event_ids: [<id>…]         # events / tail steps: each yielded envelope's id, in order
      unknown_types: [<type>…]   # …and the type of each one that was UnknownEvent
      cursor: <matcher>          # …and the helper's final cursor, e.g. { equals: c2 }
  - events: { cursor: <str>, start: <str>, types: [<type>…] }   # client.events(…), iterated to its end
    expect: {…}
  - tail: { cursor: <str>, start: <str>, types: [<type>…], take: <n> }   # client.tailEvents(…): n events, then stop
    expect: {…}
  - advance_clock_s: <int>
exchanges:
  order: sequence | any          # default sequence
  items:
    - times: <n>                 # default 1
      group: <n>                 # consecutive items sharing a group match in any order
      request:
        method: GET | POST
        path: <path>             # exact; the query goes in `query` (absent = no query)
        query: {…}
        headers: { <lowercase name>: { equals | prefix | contains | pattern: <str> } | { absent: true } | { basic: [id, secret] } | { same_as: { request: <n>, header: <name> } } }
        json: {…}                # JSON-equal
        form: {…}                # form fields, exact set
      response:
        delay_ms: <n>
        status: <int>
        headers: {…}             # content-type defaults: json, text/plain; charset=utf-8, text/event-stream
        json: {…}                # or text: <str>, or sse:
        sse:
          chunks:                # each written and flushed on its own
            - "<text>"
            - { hex: "<bytes>" }
            - { after_ms: <n> }
          then: close | reset | hold
          disconnect_within_ms: <n>   # hold only, and required there
```

`same_as` (ADR 30.9.26aa D9) passes when the header equals the one that
`exchanges.items[request]` (0-based, an earlier item) carried when it first
matched: "the same `Idempotency-Key` on every attempt".

`events` and `tail` (ADR 30.9.26aa D9) drive the helpers, not an operation:
`events` iterates `client.events(…)` to its end; `tail` takes `take` events
from `client.tailEvents(…)` and then the harness stops it, which is the
outcome `completed` (the tail never ends on its own). Each event-step's
`params` keys are the operation's parameter names (`cursor`, `start`,
`types`, `limit`); `types` is a list, sent comma-separated.

`then: reset` waits 100 ms after the last chunk before the TCP reset, so a
client that reads promptly has the bytes before it: some kernels discard a
peer's unread bytes on reset.

A stream is sent `transfer-encoding: chunked`, as the API's own are, with
each `chunks` entry one HTTP chunk. `close` sends the last chunk before the
FIN; `reset` never does, so a client sees a body with no end marker. A
close-delimited body could not show the difference on every runtime: Node's
`fetch` reads a reset there as a clean end.

## Writing a harness

A harness is a program in the language's directory, built against that
directory's library, that drives every case through the **public** API plus
the clock and sleeper seams. `make conformance-<lang>` runs it through
`conformance-server run`, which serves the cases on a free port and passes:

| Variable | Value |
|---|---|
| `LINGARA_CONFORMANCE_BASE_URL` | `http://127.0.0.1:<port>`, the client's API base URL |
| `LINGARA_CONFORMANCE_TOKEN_URL` | `http://127.0.0.1:<port>/oauth/token` |
| `LINGARA_CONFORMANCE_CONTROL_URL` | `http://127.0.0.1:<port>/__conformance` |
| `LINGARA_CONFORMANCE_OUT` | the results file to write |
| `LINGARA_CONFORMANCE_ONLY` | optional comma-separated ids, for local debugging; refused when `CI` is set |

The control surface:

| Request | Answer |
|---|---|
| `GET /__conformance/cases` | every case id, a JSON array |
| `GET /__conformance/cases/{id}` | the case as **JSON**, so no harness needs a YAML parser |
| `POST /__conformance/cases/{id}/arm` | resets and arms the case; `409` while another is armed |
| `POST /__conformance/cases/{id}/finish` | waits for held streams, then `{case, pass, mismatches}`, and disarms |

For each id, the harness arms it, builds a client from `client`, runs `steps`
against its virtual clock and recording sleeper, compares each outcome to its
`expect`, calls `finish`, and appends one line to `LINGARA_CONFORMANCE_OUT`:

```json
{"case":"k5.vocab-split-frames","lang":"typescript","library_version":"0.1.0","result":"pass","client_mismatches":[],"server_mismatches":[],"duration_ms":38}
```

`result` is `pass` only when both mismatch lists are empty; there is no
`skip`. The harness exits `0` when every line passes. `run` then fails the
language if any line is not `pass`, a case is missing, a case was reported
but never armed, or a case was armed and never finished.

A request the case did not expect is answered `599` with a JSON body naming
the expected request, and recorded as a server mismatch. Every replayed
request's `User-Agent` is checked against K6's pattern.

Comparison rules:

- JSON bodies and event data are equal after dropping keys whose value is
  `null`;
- `sleeps_s` compares whole seconds; event order is exact; `events` lists
  the events yielded, the terminal included only when it is yielded;
- `error.fields` compares every field it lists, with the contract's
  snake_case names (`status`, `code`, `message`, `retry_after`, `plan_id`,
  `served_version`, `error`, `description`, `body`, `kind`); a listed `null`
  means the field is absent. The harness maps its language's spelling onto
  these;
- timestamps (`deprecated_at`, `sunset_at`) are integer unix seconds,
  `retry_after` is whole seconds, and `link` is `{raw, target}`. In an
  expected string, `{base_url}` stands for `LINGARA_CONFORMANCE_BASE_URL`;
- the virtual clock starts at unix `1790000000` for every case;
- `parallel: n` starts n calls at once through the library's own
  concurrency, and every one of them meets `expect`. A library with no
  in-process concurrency runs them in sequence, and its documentation says
  so; every `parallel` case holds under sequential execution too;
- `cancel_after_events: n` cancels the call, with the language's native
  cancellation, after the n-th event is yielded;
- `redacted` strings must not appear in any debug, string or inspect
  rendering of the client, the token source or a raised error.

## Vectors

[`vectors/webhook-signatures.json`](vectors/webhook-signatures.json) is the
one source of truth for every library's webhook verifier (CONTRACT.md
appendix W, ADR 30.9.26aa D5). Each library's unit suite reads it through a
path relative to the repository root and runs every vector:

```json
{ "name": "rotation-second-signature-matches",
  "secrets": ["lgr_whsec_…"],
  "headers": { "webhook-id": "…", "webhook-timestamp": "…", "webhook-signature": "v1,… v1,…" },
  "body": "<the exact body, a JSON string so its bytes are unambiguous>",
  "now": 1790000120,
  "expect": { "ok": { "id": "…", "type": "…", "unknown": true } } }
```

`expect` is `{ok: {id, type, unknown?}}` (`unknown` when the parser returns
`UnknownEvent`), `{error: <reason>}`, or `{refused: true}` for a
construction refusal. `now` goes through the verifier's clock seam. A
missing header is a key left out. `verifySignature` passes every `ok` and
`malformed_payload` vector (their signatures match) and raises every other
`error` vector's reason. The secrets decode to visibly fake ASCII, and
`upstream-vector` is the Standard Webhooks suite's own fixed vector with
its secret re-prefixed `lgr_whsec_`. A vector is data, not code: adding one
needs no ADR.
