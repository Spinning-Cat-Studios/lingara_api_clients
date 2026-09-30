# frozen_string_literal: true

require "support/fake_server"

class RetryTest < Minitest::Test
  class Interrupt < StandardError; end

  PLAN_ID = "3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37"

  def setup
    @servers = []
    @sleeps = []
    @now = Time.at(1_790_000_000)
  end

  def teardown
    @servers.each(&:close)
  end

  # A server whose /v1 answers are +answers+ in turn, the last one repeated.
  def serve_answers(*answers)
    count = 0
    server = Fixtures.server do |_, conn|
      status, headers = answers[[count, answers.size - 1].min]
      count += 1
      (status == 200) ? conn.json(200, {allowance: []}) : conn.json(status, {code: "rate_limited", error: "slow down"}, headers || {})
    end
    server.tap { |s| @servers << s }
  end

  def client_for(server, **options)
    Lingara::Client.new(base_url: server.url, token_url: server.token_url, **Fixtures.credentials,
      clock: -> { @now }, sleeper: ->(seconds) { @sleeps << seconds }, **options)
  end

  def v1_requests(server)
    server.requests.count { |r| r.path.start_with?("/v1/") }
  end

  # 29.9.26t AC15: a Retry-After above the cap raises at once with
  # retry_after set; a missing one raises at once; an HTTP-date is read
  # against the clock; three 429s raise after two sleeps.
  def test_retry_after_cap_missing_header_date_and_exhaustion
    over = serve_answers([429, {"Retry-After" => "61"}], [200])
    error = assert_raises(Lingara::ApiError) { client_for(over).get_usage }
    assert_equal [429, 61, []], [error.status, error.retry_after, @sleeps]
    assert_equal 1, v1_requests(over)

    missing = serve_answers([429], [200])
    assert_raises(Lingara::ApiError) { client_for(missing).get_usage }
    assert_equal [[], 1], [@sleeps, v1_requests(missing)]

    dated = serve_answers([503, {"Retry-After" => (@now + 7).httpdate}], [200])
    assert_equal [], client_for(dated).get_usage.value.allowance
    assert_equal [7], @sleeps

    @sleeps.clear
    exhausted = serve_answers([429, {"Retry-After" => "2"}])
    error = assert_raises(Lingara::ApiError) { client_for(exhausted).get_usage }
    assert_equal [429, 2], [error.status, error.retry_after]
    assert_equal [[2, 2], 3], [@sleeps, v1_requests(exhausted)]
  end

  # 29.9.26t AC16: a Thread#raise during a Retry-After wait ends the call with
  # the caller's exception, never a K3 variant, and no further attempt.
  def test_interrupt_during_retry_wait_propagates_unwrapped
    server = serve_answers([429, {"Retry-After" => "5"}], [200])
    client = Lingara::Client.new(base_url: server.url, token_url: server.token_url, **Fixtures.credentials)
    call = Thread.new { client.get_usage }
    50.times do
      break if server.requests.size >= 2 && call.status == "sleep"
      sleep 0.1
    end
    call.raise(Interrupt, "stop waiting")
    error = assert_raises(Interrupt) { call.value }
    refute_kind_of Lingara::Error, error
    sleep 0.2
    assert_equal 1, v1_requests(server)
  end

  # 29.9.26t AC17: a GET whose server resets, and a stream_lesson_plan reset
  # after its first event, each reach the server exactly once, whatever
  # net_http_options: says about max_retries.
  def test_net_http_never_resends_a_request
    reset = Fixtures.server { |_, conn| conn.reset }.tap { |s| @servers << s }
    error = assert_raises(Lingara::TransportError) { client_for(reset, net_http_options: {max_retries: 5}).get_usage }
    assert_equal :connect, error.kind
    assert_equal 1, v1_requests(reset)

    stream = Fixtures.server do |_, conn|
      conn.sse
      conn.event("started", {plan_id: PLAN_ID})
      sleep 0.1
      conn.reset
    end
    @servers << stream
    seen = []
    error = assert_raises(Lingara::TransportError) do
      client_for(stream, net_http_options: {max_retries: 5}).stream_lesson_plan(PLAN_ID) { |event| seen << event }
    end
    assert_equal :reset, error.kind
    assert_equal ["started"], seen.map(&:event)
    assert_equal 1, v1_requests(stream)
  end
end
