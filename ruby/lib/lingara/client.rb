# frozen_string_literal: true

require "json"
require "securerandom"

module Lingara
  # Calls the Lingara API. Every C2 knob is a keyword of Client.new (D3); a
  # client is safe to share between threads.
  #
  #   client = Lingara::Client.new(client_id: ENV.fetch("LINGARA_CLIENT_ID"),
  #                                client_secret: ENV.fetch("LINGARA_CLIENT_SECRET"))
  #   client.get_usage.value
  #
  # clock: and sleeper: are testing seams (C2 D9): the clock is read for
  # token freshness and HTTP-date Retry-After values, and the sleeper is
  # handed every Retry-After wait in seconds, and every tail backoff.
  # tail_max_failures: bounds tail_events' consecutive failed reopens
  # (K5a; ADR 30.9.26aa D7).
  class Client
    include Redacted

    DEFAULT_BASE_URL = "https://api.getlingara.com"
    DEFAULT_TOKEN_URL = "https://api.getlingara.com/oauth/token"
    CREDENTIAL_KEYWORDS = %i[client_id client_secret auth scopes].freeze

    attr_reader :token_source

    def initialize(client_id: nil, client_secret: nil, auth: nil, scopes: nil, token_source: nil,
      base_url: DEFAULT_BASE_URL, token_url: DEFAULT_TOKEN_URL, version: nil, on_deprecation: nil,
      logger: DefaultLogger.new, max_attempts: 3, retry_after_cap: 60, stream_idle_timeout: 120,
      token_request_timeout: 30, user_agent_suffix: nil, clock: -> { Time.now },
      sleeper: ->(seconds) { sleep(seconds) }, net_http_options: {}, tail_max_failures: 8)
      credentials = {client_id: client_id, client_secret: client_secret, auth: auth, scopes: scopes}
      check_options(credentials, token_source, version, stream_idle_timeout)
      raise ArgumentError, "tail_max_failures: must be at least 1" unless tail_max_failures.is_a?(Integer) && tail_max_failures >= 1
      @tail_max_failures = tail_max_failures
      @base_url = base_url.to_s.chomp("/")
      @version = version
      @idle = stream_idle_timeout
      @user_agent = UserAgent.build(user_agent_suffix)
      @policy = RetryPolicy.new(max_attempts: max_attempts, retry_after_cap: retry_after_cap, clock: clock, sleeper: sleeper)
      # A tail open bypasses K4's attempt loop: its own count is the budget.
      @tail_policy = RetryPolicy.new(max_attempts: 1, retry_after_cap: retry_after_cap, clock: clock, sleeper: sleeper)
      @transport = Transport.new(net_http_options: net_http_options)
      @versions = VersionObserver.new(hook: on_deprecation, logger: logger)
      @token_source = token_source || (client_id && ClientCredentials.new(
        client_id: client_id, client_secret: client_secret, auth: auth || :basic, scopes: scopes, token_url: token_url,
        transport: @transport, policy: @policy, user_agent: @user_agent, token_request_timeout: token_request_timeout
      ))
    end

    # ── The operations, over operations.rb ────────────────────────────────

    # Streams a vocabulary list (scope vocab:generate).
    def generate_vocabulary(**body, &block)
      stream("generateVocabulary", [], body, &block)
    end

    # Streams a new lesson plan's generation (scope lesson_plans:write). A
    # plan served from the library is a lone result.
    def create_lesson_plan(**body, &block)
      stream("createLessonPlan", [], body, &block)
    end

    # Fetches a lesson plan by its id (scope lesson_plans:read).
    def get_lesson_plan(plan_id)
      json("getLessonPlan", [plan_id])
    end

    # Rejoins a lesson plan's generation by its id (scope lesson_plans:read).
    def stream_lesson_plan(plan_id, &block)
      stream("streamLessonPlan", [plan_id], nil, &block)
    end

    # Streams the tutor's reply to one turn (scope tutor:converse).
    def send_tutor_message(**body, &block)
      stream("sendTutorMessage", [], body, &block)
    end

    # This client's allowance, or its ledger if it is metered (scope
    # usage:read).
    def get_usage
      json("getUsage", [])
    end

    # The API's OpenAPI document, as a Hash. Needs no token.
    def get_open_api_document
      json("getOpenApiDocument", [])
    end

    # The API's versions. Needs no token.
    def list_api_versions
      json("listApiVersions", [])
    end

    # One API version, by its id. Needs no token.
    def get_api_version(version_id)
      json("getApiVersion", [version_id])
    end

    # The API's AsyncAPI document, the event catalogue, as a Hash. Needs no
    # token.
    def get_async_api_document
      json("getAsyncApiDocument", [])
    end

    # ── Events (ADR 30.9.26aa D6–D8) ──────────────────────────────────────

    # One page of events, an EventPage (scope events:read). +types+ is a
    # list, sent comma-separated.
    def list_events(cursor: nil, start: nil, types: nil, limit: nil)
      json("listEvents", [], query: {cursor: cursor, start: start, types: types, limit: limit})
    end

    # The feed: an Events::Feed that walks pages from +cursor+ (or +start+,
    # :latest or :oldest, without one) to the horizon and yields each
    # Events::Event. With a block, iterates it and returns the feed, whose
    # #cursor is where to resume.
    def events(cursor: nil, start: nil, types: nil, &block)
      operation = OPERATIONS.fetch("listEvents")
      fetch = ->(query) { page(operation, url_for(operation, [], query)) }
      feed = Events::Feed.new(fetch: fetch, cursor: cursor, start: start, types: types)
      block ? feed.each(&block) : feed
    end

    # The event stream itself, one connection under K5 (scope events:read):
    # it yields StreamEventsEventEvent and ends on done. tail_events is the
    # helper that resumes.
    def stream_events(cursor: nil, start: nil, types: nil, last_event_id: nil, &block)
      headers = last_event_id ? {"Last-Event-ID" => last_event_id} : {}
      stream("streamEvents", [], nil, query: {cursor: cursor, start: start, types: types}, headers: headers, &block)
    end

    # The tail (K5a): an Events::Tail that yields each Events::Event and
    # reconnects from its #cursor after any ending, until
    # tail_max_failures: consecutive failures. It never ends on its own:
    # leave the block to stop it.
    def tail_events(cursor: nil, start: nil, types: nil, &block)
      operation = OPERATIONS.fetch("streamEvents")
      # The first request's URL, repeated by every reopen: start only
      # without a cursor, which travels as Last-Event-ID instead.
      url = url_for(operation, [], {start: (start unless cursor), types: types})
      open = lambda do |last_event_id, on_start, &consume|
        headers = last_event_id ? {"Last-Event-ID" => last_event_id} : {}
        pipeline(operation, url, nil, on_start: on_start, headers: headers, policy: @tail_policy, &consume)
      end
      tail = Events::Tail.new(open: open, cursor: cursor, max_failures: @tail_max_failures, policy: @policy)
      block ? tail.each(&block) : tail
    end

    # Sends one Events::InboundEvent (scope events:write, and
    # lesson_plans:write when it asks for generation) and returns the
    # InboundEventAccepted. Every attempt carries one Idempotency-Key: the
    # caller's, unchanged, or a UUIDv4 made once for this call (K4).
    def send_event(event, idempotency_key: nil)
      raise ArgumentError, "event must be a Lingara::Events::InboundEvent" unless event.is_a?(Events::InboundEvent)
      key = idempotency_key || SecureRandom.uuid
      json("sendEvent", [], body: JSON.generate(event.to_hash), headers: {"Idempotency-Key" => key})
    end

    # ── Embedding (ADR 1.10.26w) ──────────────────────────────────────────

    # Mints a player's embed token (scope embed:mint, a metered client
    # only): player_ref:, and optionally scopes: (a list) and origin:. Call
    # it on your server, never on a player's device. Returns a MintedToken,
    # whose token renders [REDACTED]. The token lives 900 s and is never
    # refreshed: mint again when the player kit asks.
    def create_embed_token(**body)
      operation = OPERATIONS.fetch("createEmbedToken")
      json("createEmbedToken", [], body: payload(operation, body), decode: MintedToken.method(:decode))
    end

    # Deletes a player and revokes its tokens (scope embed:mint). An unknown
    # player_ref is a success too, so a retry is safe, and it works while
    # embedding is switched off. Returns a Response whose value is nil.
    def delete_embed_player(player_ref)
      no_content("deleteEmbedPlayer", [player_ref])
    end

    # Streams an NPC's reply to one line (scope embed:play, from an embed
    # token or a metered client's own token): delta and notice events, ending
    # on done. Each turn spends the player's NPC cells and the payer's, so it
    # is sent once, never retried: a 429 or 503 raises ApiError at once,
    # retry_after included, and the caller decides whether to send again.
    # The window is the schema's: at most 12 history entries, line and each
    # entry at most 500 characters; send an NPC reply back cut to its first
    # 500. 403 embed_needs_metered and 422 safety_input_flagged (say
    # something else) are refusals no retry helps.
    def send_dialogue_turn(**body, &block)
      stream("sendDialogueTurn", [], body, policy: @tail_policy, &block)
    end

    def to_s
      inspect
    end

    def inspect
      "#<Lingara::Client base_url=#{@base_url.inspect} version=#{@version.inspect} token_source=#{@token_source.inspect}>"
    end

    private

    def check_options(credentials, token_source, version, idle)
      if token_source && credentials.any? { |_, value| !value.nil? }
        raise ArgumentError, "token_source: cannot be combined with #{CREDENTIAL_KEYWORDS.map { |k| "#{k}:" }.join(", ")}"
      end
      if credentials[:client_id].nil? != credentials[:client_secret].nil?
        raise ArgumentError, "client_id: and client_secret: are given together or not at all"
      end
      raise ArgumentError, "version: must not be empty" if version == ""
      raise ArgumentError, "stream_idle_timeout: must be a positive number" unless idle.is_a?(Numeric) && idle.positive?
    end

    # +body+ is JSON text already; +headers+ join every attempt's. +decode+
    # replaces the generated model's decoding (create_embed_token's).
    def json(id, args, query: nil, body: nil, headers: {}, decode: nil)
      operation = OPERATIONS.fetch(id)
      url = url_for(operation, args, query)
      decode ||= ->(text) { Decoding.response(operation[:response], text) }
      pipeline(operation, url, body, headers: headers) do |response, _phase, observe|
        served = observe.call(response)
        Response.new(value: decode.call(response.body.to_s), served_version: served)
      end
    end

    # An operation whose success has no body (D4): no request body and no
    # Content-Type, and any 2xx body is discarded unread. Called by name: a
    # route's `response: nil` already means "a plain Hash".
    def no_content(id, args)
      operation = OPERATIONS.fetch(id)
      pipeline(operation, url_for(operation, args), nil) do |response, _phase, observe|
        Response.new(value: nil, served_version: observe.call(response))
      end
    end

    # The request model's constructor refuses a bad keyword before any
    # request is sent: a programming error, like Client.new's.
    def payload(operation, body)
      body && JSON.generate(Lingara.const_get(operation[:request_body]).new(body).to_hash)
    end

    # One page of the feed as its raw Hash, so each item reaches
    # Events.decode as the bytes the server sent (D6).
    def page(operation, url)
      pipeline(operation, url, nil) do |response, _phase, observe|
        observe.call(response)
        Decoding.page(response.body.to_s)
      end
    end

    # +policy+ is the single-attempt one for a stream that must not be
    # retried (send_dialogue_turn).
    def stream(id, args, body, query: nil, headers: {}, policy: @policy, &block)
      operation = OPERATIONS.fetch(id)
      url = url_for(operation, args, query)
      send = ->(*call, **options, &consume) { pipeline(*call, headers: headers, policy: policy, **options, &consume) }
      events = EventStream.new(pipeline: send, operation: operation, url: url, body: payload(operation, body))
      block ? events.run(&block) : events
    end

    # Sends one call: its token and the one 401 retry, the Retry-After loop,
    # and the refusal mapping. Yields a 2xx response, its Transport::Phase and
    # the version observer, and returns what the block returns. A client with
    # no token source sends an operation that needs one without
    # Authorization; the server's 401 is the answer. +policy+ is the tail's
    # single-attempt one for a tail open (K5a).
    def pipeline(operation, url, body, on_start: nil, headers: {}, policy: @policy, &on_success)
      send_all = lambda do |token|
        policy.run { attempt(operation, url, body, [token, headers], on_start, &on_success) }
      end
      result = if @token_source && operation[:needs_token]
        RetryPolicy.with_token_retry(@token_source, &send_all)
      else
        send_all.call(nil)
      end
      return result.value if result.done
      raise Refusal.error(:v1, result.response, result.body, @policy.now)
    end

    # +auth+ is the token and the call's own headers.
    def attempt(operation, url, body, auth, on_start)
      token, extra = auth
      observe = ->(response) { @versions.observe(response, url) }
      result = @transport.request(operation[:method], url, headers(operation, body, token).merge(extra), body: body,
        read_timeout: operation[:stream] && @idle, secrets: token ? [token.expose_secret] : [], on_start: on_start) do |response, phase|
        next Attempt.new(response: response, body: response.body.to_s) unless (200..299).cover?(response.code.to_i)
        Attempt.new(value: yield(response, phase, observe), done: true)
      end
      # Phase#leave hands back the stream's value directly.
      result.is_a?(Attempt) ? result : Attempt.new(value: result, done: true)
    end

    def headers(operation, body, token)
      headers = {"Accept" => operation[:stream] ? "text/event-stream" : "application/json", "User-Agent" => @user_agent}
      headers["Content-Type"] = "application/json" if body
      headers["Authorization"] = "Bearer #{token.expose_secret}" if token
      headers["Lingara-Version"] = @version if @version
      headers
    end

    # The base URL and the route's path, each path parameter percent-encoded
    # but for A–Z a–z 0–9 - . _ ~, as the other libraries do. +query+'s nil
    # values are left out and a list is one comma-separated value
    # (`explode: false`), encoded the same way.
    def url_for(operation, args, query = nil)
      path = operation[:path].dup
      operation[:path_params].zip(args).each do |name, value|
        raise ArgumentError, "#{name} must be a non-empty String" unless value.is_a?(String) && !value.empty?
        path.sub!("{#{name}}", encode(value))
      end
      pairs = (query || {}).reject { |_, value| value.nil? }.map { |name, value| "#{name}=#{encode(Array(value).join(","))}" }
      pairs.empty? ? @base_url + path : "#{@base_url}#{path}?#{pairs.join("&")}"
    end

    def encode(value)
      value.to_s.b.gsub(/[^A-Za-z0-9\-._~]/n) { |c| format("%%%02X", c.ord) }
    end
  end
end
