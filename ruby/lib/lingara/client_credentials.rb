# frozen_string_literal: true

require "json"
require "timeout"
require "uri"

module Lingara
  # K1: the OAuth 2.0 client-credentials token source (CONTRACT.md K1; D5).
  #
  # It caches one token and replaces it min(60 s, expires_in / 2) before it
  # expires, shares one exchange between concurrent callers, and clears only
  # the token a 401 was answered with. The exchange runs on a thread of its
  # own, and every caller, the one that started it included, waits on a
  # ConditionVariable: a caller's interrupt ends only that caller's wait,
  # while the flight completes and caches its token. Detached from every
  # caller, each HTTP attempt of it carries token_request_timeout:.
  #
  # Any object with #token (returning an AccessToken) and #invalidate(token)
  # is a token source; this is the one a client builds from client_id: and
  # client_secret:.
  class ClientCredentials
    include Redacted

    # The one exchange every concurrent caller waits on. +pid+ is the process
    # that started it: a thread does not survive fork.
    Flight = Struct.new(:pid, :done, :token, :error)
    Cached = Struct.new(:token, :stale_at)

    attr_reader :client_id

    def initialize(client_id:, client_secret:, token_url:, transport:, policy:, user_agent:, auth: :basic, scopes: nil,
      token_request_timeout: 30)
      raise ArgumentError, "auth: must be :basic or :post" unless %i[basic post].include?(auth)
      raise ArgumentError, "token_request_timeout: must be a positive number" unless token_request_timeout.is_a?(Numeric) && token_request_timeout.positive?
      @client_id = client_id
      @secret = client_secret
      @config = {token_url: token_url, transport: transport, user_agent: user_agent, auth: auth, scopes: Array(scopes), timeout: token_request_timeout}
      @policy = policy
      @mutex = Mutex.new
      @cv = ConditionVariable.new
      @cached = nil
      @flight = nil
    end

    # The raw client secret: the one accessor that does not redact.
    def expose_secret
      @secret
    end

    # The cached token while it is fresh; otherwise the result of the one
    # flight, started if none is running.
    def token
      @mutex.synchronize do
        # A flight another process started has no thread here (D5, fork).
        @flight = nil if @flight && @flight.pid != Process.pid
        return @cached.token if @cached && @policy.now < @cached.stale_at
        flight = @flight ||= start_flight
        @cv.wait(@mutex) until flight.done
        raise flight.error if flight.error
        flight.token
      end
    end

    # Forgets +token+ only if it is still the cached one. During an exchange
    # it is a no-op: nothing is cached yet.
    def invalidate(token)
      @mutex.synchronize do
        @cached = nil if @cached && @cached.token == token
      end
    end

    def to_s
      inspect
    end

    def inspect
      "#<Lingara::ClientCredentials client_id=#{@client_id.inspect} client_secret=#{REDACTED}>"
    end

    private

    # Called with @mutex held.
    def start_flight
      flight = Flight.new(Process.pid, false, nil, nil)
      thread = Thread.new { fly(flight) }
      thread.name = "lingara-token"
      thread.report_on_exception = false
      flight
    end

    def fly(flight)
      cached = exchange
    rescue => e
      error = e
    ensure
      @mutex.synchronize do
        @flight = nil if @flight.equal?(flight)
        if cached
          @cached = cached
          flight.token = cached.token
        else
          flight.error = error || TransportError.new(:connect, "the token exchange ended without an answer")
        end
        flight.done = true
        @cv.broadcast
      end
    end

    def exchange
      sent_at = nil
      attempt = @policy.run do
        # obtained_at is when the request that succeeded was sent.
        sent_at = @policy.now
        post
      end
      unless (200..299).cover?(attempt.status)
        raise Refusal.error(:token, attempt.response, attempt.body, @policy.now)
      end
      token, lifetime = grant(attempt.body)
      skew = [60, lifetime / 2.0].min
      Cached.new(token, sent_at + lifetime - skew)
    end

    # One exchange attempt and its whole body, under the token request
    # timeout. Retry-After sleeps happen between attempts, so they never
    # count against it.
    def post
      Timeout.timeout(@config[:timeout], TokenAttemptTimeout) do
        @config[:transport].request("POST", @config[:token_url], headers, body: form, secrets: [@secret]) do |response, _phase|
          Attempt.new(response: response, body: response.body.to_s)
        end
      end
    rescue TokenAttemptTimeout
      raise TransportError.new(:timeout, "the token request timed out")
    end

    def headers
      headers = {
        "Content-Type" => "application/x-www-form-urlencoded",
        "Accept" => "application/json",
        "User-Agent" => @config[:user_agent]
      }
      headers["Authorization"] = basic_auth if @config[:auth] == :basic
      headers
    end

    # The grant, any scopes, and the credentials only under
    # client_secret_post: never both places.
    def form
      pairs = [["grant_type", "client_credentials"]]
      pairs << ["scope", @config[:scopes].join(" ")] unless @config[:scopes].empty?
      pairs.push(["client_id", @client_id], ["client_secret", @secret]) if @config[:auth] == :post
      URI.encode_www_form(pairs)
    end

    # Basic base64(form(id) ":" form(secret)), each half form-encoded per
    # RFC 6749 §2.3.1. [s].pack("m0") is base64 with no bundled gem.
    def basic_auth
      pair = "#{URI.encode_www_form_component(@client_id)}:#{URI.encode_www_form_component(@secret)}"
      "Basic #{[pair].pack("m0")}"
    end

    # A 200's token and lifetime: malformed unless it has an access_token, an
    # expires_in and a Bearer token_type.
    def grant(body)
      fields = JSON.parse(body)
      raw, lifetime, type = fields.values_at("access_token", "expires_in", "token_type") if fields.is_a?(Hash)
      unless raw.is_a?(String) && !raw.empty? && lifetime.is_a?(Numeric) && lifetime >= 0 && type.is_a?(String) && type.casecmp?("bearer")
        raise TransportError.new(:malformed_response, "the token response lacks access_token, expires_in or a Bearer token_type")
      end
      [AccessToken.new(raw), lifetime]
    rescue JSON::ParserError
      raise TransportError.new(:malformed_response, "the token response is not JSON")
    end
  end
end
