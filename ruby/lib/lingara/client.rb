# frozen_string_literal: true

require "json"

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
  # handed every Retry-After wait in seconds.
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
      sleeper: ->(seconds) { sleep(seconds) }, net_http_options: {})
      credentials = {client_id: client_id, client_secret: client_secret, auth: auth, scopes: scopes}
      check_options(credentials, token_source, version, stream_idle_timeout)
      @base_url = base_url.to_s.chomp("/")
      @version = version
      @idle = stream_idle_timeout
      @user_agent = UserAgent.build(user_agent_suffix)
      @policy = RetryPolicy.new(max_attempts: max_attempts, retry_after_cap: retry_after_cap, clock: clock, sleeper: sleeper)
      @transport = Transport.new(net_http_options: net_http_options)
      @versions = VersionObserver.new(hook: on_deprecation, logger: logger)
      @token_source = token_source || (client_id && ClientCredentials.new(
        client_id: client_id, client_secret: client_secret, auth: auth || :basic, scopes: scopes, token_url: token_url,
        transport: @transport, policy: @policy, user_agent: @user_agent, token_request_timeout: token_request_timeout
      ))
    end

    # ── The nine operations, over operations.rb ───────────────────────────

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

    def json(id, args)
      operation = OPERATIONS.fetch(id)
      url = url_for(operation, args)
      pipeline(operation, url, nil) do |response, _phase, observe|
        served = observe.call(response)
        Response.new(value: Decoding.response(operation[:response], response.body.to_s), served_version: served)
      end
    end

    def stream(id, args, body, &block)
      operation = OPERATIONS.fetch(id)
      url = url_for(operation, args)
      # The request model's constructor refuses a bad keyword before any
      # request is sent: a programming error, like Client.new's.
      payload = body && JSON.generate(Lingara.const_get(operation[:request_body]).new(body).to_hash)
      events = EventStream.new(pipeline: method(:pipeline), operation: operation, url: url, body: payload)
      block ? events.run(&block) : events
    end

    # Sends one call: its token and the one 401 retry, the Retry-After loop,
    # and the refusal mapping. Yields a 2xx response, its Transport::Phase and
    # the version observer, and returns what the block returns. A client with
    # no token source sends an operation that needs one without
    # Authorization; the server's 401 is the answer.
    def pipeline(operation, url, body, on_start: nil, &on_success)
      send_all = lambda do |token|
        @policy.run { attempt(operation, url, body, token, on_start, &on_success) }
      end
      result = if @token_source && operation[:needs_token]
        RetryPolicy.with_token_retry(@token_source, &send_all)
      else
        send_all.call(nil)
      end
      return result.value if result.done
      raise Refusal.error(:v1, result.response, result.body, @policy.now)
    end

    def attempt(operation, url, body, token, on_start)
      observe = ->(response) { @versions.observe(response, url) }
      result = @transport.request(operation[:method], url, headers(operation, body, token), body: body,
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
    # but for A–Z a–z 0–9 - . _ ~, as the other libraries do.
    def url_for(operation, args)
      path = operation[:path].dup
      operation[:path_params].zip(args).each do |name, value|
        raise ArgumentError, "#{name} must be a non-empty String" unless value.is_a?(String) && !value.empty?
        path.sub!("{#{name}}", value.b.gsub(/[^A-Za-z0-9\-._~]/n) { |c| format("%%%02X", c.ord) })
      end
      @base_url + path
    end
  end
end
