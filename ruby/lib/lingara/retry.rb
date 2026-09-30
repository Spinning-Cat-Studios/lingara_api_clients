# frozen_string_literal: true

require "time"

module Lingara
  # K4's Retry-After loop around one HTTP request, and K1's one 401 retry
  # around a /v1 call (CONTRACT.md K1, K4).
  #
  # Each HTTP request has its own budget of max_attempts. The token exchange
  # is one request and the /v1 request another, and the 401 retry sends the
  # /v1 request again with a fresh budget. Every decision is made on the
  # status line and headers alone, before any body byte reaches the caller.
  class RetryPolicy
    # A delta above this is far past any cap; clamping keeps it an Integer
    # of sane size.
    MAX_DELTA = 2**32

    attr_reader :max_attempts, :retry_after_cap, :clock, :sleeper

    def initialize(max_attempts:, retry_after_cap:, clock:, sleeper:)
      raise ArgumentError, "max_attempts: must be at least 1" unless max_attempts.is_a?(Integer) && max_attempts >= 1
      raise ArgumentError, "retry_after_cap: must be a non-negative number" unless retry_after_cap.is_a?(Numeric) && retry_after_cap >= 0
      @max_attempts = max_attempts
      @retry_after_cap = retry_after_cap
      @clock = clock
      @sleeper = sleeper
    end

    def now
      @clock.call
    end

    # Retry-After as whole seconds: delta-seconds, or an HTTP-date read
    # against +now+ (max(0, date − now), rounded up). Absent or unreadable is
    # nil.
    def self.parse_retry_after(value, now)
      value = value.to_s.strip
      return nil if value.empty?
      return [value.to_i, MAX_DELTA].min if value.match?(/\A\d+\z/)
      at = Time.httpdate(value)
      [(at - now).ceil, 0].max
    rescue ArgumentError
      nil
    end

    # How long to wait before trying again, or nil to hand the response back.
    def wait_for(response, tries)
      return nil unless [429, 503].include?(response.code.to_i)
      return nil if tries >= @max_attempts
      wait = RetryPolicy.parse_retry_after(response["Retry-After"], now)
      return nil if wait.nil? || wait > @retry_after_cap
      wait
    end

    # Runs +attempt+ (which returns an Attempt) until it answers something
    # other than a retryable 429 or 503, or the attempts run out, and returns
    # the last Attempt. A TransportError is never retried: it propagates.
    def run
      tries = 1
      loop do
        attempt = yield
        wait = attempt.response && wait_for(attempt.response, tries)
        return attempt unless wait
        @sleeper.call(wait)
        tries += 1
      end
    end

    # K1's one 401 retry: send with a token; on a 401, forget that token
    # (only if it is still the cached one), get another and send once more.
    # A second 401 is handed back for the caller to map.
    def self.with_token_retry(tokens)
      first = tokens.token
      attempt = yield first
      return attempt unless attempt.status == 401
      tokens.invalidate(first)
      yield tokens.token
    end
  end

  # One HTTP attempt's result: either a +value+ the success handler produced,
  # or a refused +response+ with the +body+ read from it.
  Attempt = Struct.new(:response, :body, :value, :done) do
    def status
      response&.code&.to_i
    end
  end
end
