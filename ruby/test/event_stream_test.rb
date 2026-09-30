# frozen_string_literal: true

require "support/fake_server"

class EventStreamTest < Minitest::Test
  PLAN_ID = "3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37"
  META = {meta: {level: 2, source_lang: "en", target_lang: "zh", framework: "HSK", count: 1, ai_generated: true}}.freeze
  ITEM = {word: "你好", translation: "hello"}.freeze
  VOCAB = {level: 2, source_lang: "en", target_lang: "zh"}.freeze
  VIEW = File.expand_path("../../spec/generator/openapi.3.0.json", __dir__)

  def setup
    @servers = []
  end

  def teardown
    @servers.each(&:close)
  end

  def serve(&handler)
    Fixtures.server(&handler).tap { |server| @servers << server }
  end

  def client_for(server, **options)
    server.client(**Fixtures.credentials, **options)
  end

  def v1_requests(server)
    server.requests.count { |r| r.path.start_with?("/v1/") }
  end

  def now
    Process.clock_gettime(Process::CLOCK_MONOTONIC)
  end

  # 29.9.26t AC18: break closes the socket; a second each raises IOError;
  # close mid-next closes the socket and is idempotent; close before any
  # iteration sends nothing, and a later each raises IOError.
  def test_break_closes_socket_and_stream_is_single_use
    closed = Queue.new
    server = serve do |_, conn|
      conn.sse
      conn.event("started", META)
      conn.event("item", ITEM)
      closed << conn.closed_by_peer?(2)
    end
    client = client_for(server)
    client.generate_vocabulary(**VOCAB) { |_| break }
    assert closed.pop, "break left the socket open"

    stream = client.generate_vocabulary(**VOCAB)
    assert_equal "started", stream.first.event
    assert closed.pop
    assert_raises(IOError) { stream.each { |_| nil } }

    stream = client.generate_vocabulary(**VOCAB)
    assert_equal "started", stream.next.event
    stream.close
    assert closed.pop, "close left the socket open"
    stream.close
    assert_raises(IOError) { stream.each { |_| nil } }

    before = v1_requests(server)
    unused = client.generate_vocabulary(**VOCAB)
    unused.close
    assert_raises(IOError) { unused.each { |_| nil } }
    assert_equal before, v1_requests(server)
  end

  # 29.9.26t AC19: a block-less stream sends nothing before its first each or
  # next, and its served_version is nil until then.
  def test_block_less_stream_sends_on_first_iteration
    server = serve do |_, conn|
      conn.sse("Lingara-Version" => "2026-09-glowing-hoatzin")
      conn.event("started", META)
      conn.event("done", {})
    end
    client = client_for(server)
    stream = client.generate_vocabulary(**VOCAB)
    sleep 0.1
    assert_equal 0, server.requests.size
    assert_nil stream.served_version
    assert_equal ["started"], stream.map(&:event)
    assert_equal "2026-09-glowing-hoatzin", stream.served_version
    assert_equal 1, v1_requests(server)

    stream = client.generate_vocabulary(**VOCAB)
    assert_equal "started", stream.next.event
    assert_raises(StopIteration) { stream.next }
  end

  # 29.9.26t AC20: with a 0.5 s idle timeout, 1 s of silence while a read is
  # pending is :timeout; keepalives every 50 ms keep it open; and a consumer
  # holding an event for 1 s (in the block, and between two next calls)
  # while the server is silent receives the next event, which the server
  # sends 0.2 s after the read resumes.
  def test_idle_timeout_is_an_option_and_keepalive_resets_it
    silent = serve do |_, conn|
      conn.sse
      conn.event("started", META)
      sleep 1.5
    end
    seen = []
    error = assert_raises(Lingara::TransportError) do
      client_for(silent, stream_idle_timeout: 0.5).generate_vocabulary(**VOCAB) { |event| seen << event.event }
    end
    assert_equal [:timeout, ["started"]], [error.kind, seen]

    kept = serve do |_, conn|
      conn.sse
      20.times do
        conn.chunk(": keepalive\n\n")
        sleep 0.05
      end
      conn.event("item", ITEM)
      conn.event("done", {})
    end
    seen = []
    client_for(kept, stream_idle_timeout: 0.5).generate_vocabulary(**VOCAB) { |event| seen << event.event }
    assert_equal ["item"], seen

    resumed = Queue.new
    held = serve do |_, conn|
      conn.sse
      conn.event("started", META)
      resumed.pop
      sleep 0.2
      conn.event("item", ITEM)
      conn.event("done", {})
    end
    seen = []
    client = client_for(held, stream_idle_timeout: 0.5)
    client.generate_vocabulary(**VOCAB) do |event|
      seen << event.event
      next unless event.event == "started"
      sleep 1
      resumed << true
    end
    assert_equal %w[started item], seen

    stream = client.generate_vocabulary(**VOCAB)
    assert_equal "started", stream.next.event
    sleep 1
    resumed << true
    assert_equal "item", stream.next.event
    stream.close
  end

  # 29.9.26t AC21: a known event whose data is a JSON array is
  # :malformed_event and never yielded; an unknown event name is skipped.
  def test_wrong_shaped_data_is_malformed_event
    server = serve do |_, conn|
      conn.sse
      conn.chunk("event: surprise\ndata: {\"new\":true}\n\n")
      conn.event("started", META)
      conn.chunk("event: item\ndata: [1,2]\n\n")
      conn.event("done", {})
    end
    seen = []
    error = assert_raises(Lingara::TransportError) do
      client_for(server).generate_vocabulary(**VOCAB) { |event| seen << event.event }
    end
    assert_equal [:malformed_event, ["started"]], [error.kind, seen]
  end

  # 29.9.26t AC23: each stream operation ends on its own terminal (result and
  # pending yielded, done not) and returns promptly while the server holds
  # the connection open; the generated terminal table matches the view's
  # endsOn, and every terminal is among its operation's events.
  def test_each_operation_ends_on_its_own_terminal
    client = client_for(serve { |request, conn| terminal_then_hold(request, conn) })
    calls = {
      generate_vocabulary: -> { client.generate_vocabulary(**VOCAB).map(&:event) },
      create_lesson_plan: -> { client.create_lesson_plan(context: "market", source_lang: "en", target_lang: "zh", level: 2).map(&:event) },
      send_tutor_message: -> { client.send_tutor_message(message: "hi", source_lang: "en", target_lang: "zh").map(&:event) },
      stream_lesson_plan: -> { client.stream_lesson_plan(PLAN_ID).map(&:event) }
    }
    expected = {generate_vocabulary: [], create_lesson_plan: ["result"], send_tutor_message: ["delta"], stream_lesson_plan: ["pending"]}
    calls.each do |name, call|
      started = now
      assert_equal expected[name], call.call, name
      assert_operator now - started, :<, 1, "#{name} waited on a held connection"
    end
    assert_terminals_match_view
  end

  def assert_terminals_match_view
    view = JSON.parse(File.read(VIEW))
    streams = Lingara::OPERATIONS.select { |_, op| op[:stream] }
    assert_equal view["x-lingara-streams"].map { |e| e["operationId"] }.sort, streams.keys.sort
    view["x-lingara-streams"].each do |entry|
      stream = streams.fetch(entry["operationId"])[:stream]
      assert_equal entry["endsOn"].sort, stream[:ends].keys.sort
      assert_equal :raise, stream[:ends][entry["error"]]
      assert_empty stream[:ends].keys - stream[:events].keys
    end
  end

  # 29.9.26t AC24: an error event raises ApiError with status 200, code,
  # message, plan_id and served_version; never yielded, never retried.
  def test_error_event_raises_api_error_with_plan_id
    server = serve do |_, conn|
      conn.sse("Lingara-Version" => "2026-09-glowing-hoatzin")
      conn.event("started", {plan_id: PLAN_ID})
      conn.event("error", {code: "generation_failed", message: "The plan could not be generated.", plan_id: PLAN_ID})
    end
    seen = []
    error = assert_raises(Lingara::ApiError) do
      client_for(server).create_lesson_plan(context: "market", source_lang: "en", target_lang: "zh", level: 2) { |e| seen << e.event }
    end
    assert_equal [200, "generation_failed", "The plan could not be generated.", PLAN_ID, "2026-09-glowing-hoatzin"],
      [error.status, error.code, error.message, error.plan_id, error.served_version]
    assert_equal ["started"], seen
    assert_equal 1, v1_requests(server)
  end

  private

  # Each operation's terminal event, then the connection held open.
  def terminal_then_hold(request, conn)
    conn.sse
    case request.path
    when "/v1/vocab/stream" then conn.event("done", {})
    when "/v1/lesson-plans" then conn.event("result", {plan: plan})
    when "/v1/tutor/message" then conn.event("delta", {text: "hi"}) && conn.event("done", {})
    else conn.event("pending", {plan_id: PLAN_ID, status: "generating"})
    end
    sleep 3
  end

  def plan
    {id: PLAN_ID, status: "complete", source_lang: "en", target_lang: "zh", level: 2, created_at: "2026-09-23T10:00:00Z", ai_generated: true}
  end
end
