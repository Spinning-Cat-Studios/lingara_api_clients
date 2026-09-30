# frozen_string_literal: true

require "support/fake_server"

class ClientCredentialsTest < Minitest::Test
  class Interrupt < StandardError; end

  def setup
    @now = Time.at(1_790_000_000)
    @servers = []
  end

  def teardown
    @servers.each(&:close)
  end

  def serve(&handler)
    FakeServer.new(&handler).tap { |server| @servers << server }
  end

  def credentials(server, **options)
    policy = Lingara::RetryPolicy.new(max_attempts: 3, retry_after_cap: 60, clock: -> { @now }, sleeper: ->(_) {})
    Lingara::ClientCredentials.new(client_id: Fixtures::CLIENT_ID, client_secret: Fixtures::SECRET, token_url: server.token_url,
      transport: Lingara::Transport.new(net_http_options: {}), policy: policy, user_agent: Lingara::UserAgent.build, **options)
  end

  def in_threads(count, &block)
    Array.new(count) { Thread.new(&block) }.map do |thread|
      thread.value
    rescue => e
      e
    end
  end

  # 29.9.26t AC7: eight threads on an empty cache cause one exchange; when it
  # fails, all eight get the same error and nothing is cached.
  def test_single_flight_shares_one_exchange_and_caches_no_failure
    good = serve do |_, conn|
      sleep 0.2
      Fixtures.token_response(conn, "lgr_at_flight")
    end
    source = credentials(good)
    tokens = in_threads(8) { source.token }
    assert_equal ["lgr_at_flight"], tokens.map(&:expose_secret).uniq
    assert_equal 1, good.requests.size

    bad = serve do |_, conn|
      sleep 0.2
      conn.json(500, {error: "server_error"})
    end
    source = credentials(bad)
    errors = in_threads(8) { source.token }
    assert(errors.all?(Lingara::OAuthError))
    assert_equal 1, errors.uniq(&:object_id).size
    assert_equal 1, bad.requests.size
    assert_raises(Lingara::OAuthError) { source.token }
    assert_equal 2, bad.requests.size
  end

  # 29.9.26t AC8: a 3600 s token is reused at 3539 s and replaced at 3541 s
  # after the request was sent; a 40 s token is stale at 20 s.
  def test_refreshes_at_min_of_sixty_seconds_and_half_lifetime
    lifetime = 3600
    count = 0
    server = serve do |_, conn|
      count += 1
      Fixtures.token_response(conn, "lgr_at_#{count}", expires_in: lifetime)
    end
    source = credentials(server)
    start = @now
    assert_equal "lgr_at_1", source.token.expose_secret
    @now = start + 3539
    assert_equal "lgr_at_1", source.token.expose_secret
    @now = start + 3541
    assert_equal "lgr_at_2", source.token.expose_secret

    lifetime = 40
    short = credentials(server)
    start = @now
    first = short.token.expose_secret
    @now = start + 19
    assert_equal first, short.token.expose_secret
    @now = start + 20
    refute_equal first, short.token.expose_secret
  end

  # 29.9.26t AC9: invalidating an older token leaves a newer cached one.
  def test_invalidate_is_compare_and_clear
    count = 0
    server = serve do |_, conn|
      count += 1
      Fixtures.token_response(conn, "lgr_at_#{count}")
    end
    source = credentials(server)
    old = source.token
    source.invalidate(old)
    newer = source.token
    refute_equal old, newer
    source.invalidate(old)
    assert_equal newer, source.token
    assert_equal 2, server.requests.size
  end

  # 29.9.26t AC10: a Thread#raise into a waiter, the flight's starter
  # included, reaches that caller unwrapped at once; the flight completes
  # and its token is cached for the next caller.
  def test_interrupted_waiter_leaves_flight_running
    server = serve do |_, conn|
      sleep 0.6
      Fixtures.token_response(conn, "lgr_at_survivor")
    end
    source = credentials(server)
    starter = Thread.new { source.token }
    sleep 0.05
    waiter = Thread.new { source.token }
    sleep 0.1
    [starter, waiter].each do |thread|
      interrupted = Process.clock_gettime(Process::CLOCK_MONOTONIC)
      thread.raise(Interrupt, "the caller's own")
      error = assert_raises(Interrupt) { thread.value }
      assert_equal "the caller's own", error.message
      assert_operator Process.clock_gettime(Process::CLOCK_MONOTONIC) - interrupted, :<, 0.3
    end
    sleep 0.7
    assert_equal "lgr_at_survivor", source.token.expose_secret
    assert_equal 1, server.requests.size
  end

  # 29.9.26t AC11: a stalled token endpoint and one that trickles its headers
  # a byte at a time both fail the attempt at token_request_timeout: with
  # TransportError :timeout for every waiter; the next token call starts a
  # fresh exchange.
  def test_token_request_timeout_bounds_each_attempt
    stalled = serve { |_, _conn| sleep 2 }
    trickle = serve do |_, conn|
      "HTTP/1.1 200 OK\r\nX-Slow: #{"a" * 40}".each_char do |c|
        conn.socket.write(c)
        conn.socket.flush
        sleep 0.05
      end
    end
    [stalled, trickle].each do |server|
      source = credentials(server, token_request_timeout: 0.3)
      started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
      errors = in_threads(2) { source.token }
      assert(errors.all? { |e| e.is_a?(Lingara::TransportError) && e.kind == :timeout }, errors.inspect)
      assert_operator Process.clock_gettime(Process::CLOCK_MONOTONIC) - started, :<, 1.5
      assert_raises(Lingara::TransportError) { source.token }
      assert_equal 2, server.requests.size
    end
  end

  # 29.9.26t AC36: a process forked while its parent's flight is stalled makes
  # its own exchange on token and returns.
  def test_forked_child_discards_parent_flight
    skip "fork is unavailable here" unless Process.respond_to?(:fork)
    count = 0
    server = serve do |_, conn|
      count += 1
      sleep 3 if count == 1
      Fixtures.token_response(conn, "lgr_at_child")
    end
    source = credentials(server)
    Thread.new { source.token }.report_on_exception = false
    sleep 0.2
    reader, writer = IO.pipe
    pid = fork do
      reader.close
      writer.write(source.token.expose_secret)
      writer.close
      exit!(0)
    end
    writer.close
    waited = Thread.new { Process.wait2(pid) }
    assert waited.join(2), "the forked child waited on its parent's flight"
    assert waited.value[1].success?
    assert_equal "lgr_at_child", reader.read
  end
end
