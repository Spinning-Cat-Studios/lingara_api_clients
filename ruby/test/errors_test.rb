# frozen_string_literal: true

require "timeout"
require "support/fake_server"

class ErrorsTest < Minitest::Test
  PLAN = {id: "3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37", status: "complete", source_lang: "en", target_lang: "zh", level: 2,
          created_at: "2026-09-23T10:00:00Z", ai_generated: true}.freeze

  def setup
    @servers = []
  end

  def teardown
    @servers.each(&:close)
  end

  def serve(**options, &handler)
    FakeServer.new(**options, &handler).tap { |server| @servers << server }
  end

  def credentialed(server, **options)
    server.client(**Fixtures.credentials, **options)
  end

  # 29.9.26t AC13: a plain-text 503 from either endpoint is MaintenanceError;
  # a non-envelope /v1 502 is ApiError http_502; a non-RFC 6749 token 500 is
  # OAuthError http_500; each is a Lingara::Error.
  def test_responses_map_to_the_error_family
    maintenance = serve { |_, conn| conn.text(503, "Service is under maintenance. Please try again later.") }
    token = assert_raises(Lingara::MaintenanceError) { credentialed(maintenance).get_usage }
    v1 = assert_raises(Lingara::MaintenanceError) { maintenance.client.list_api_versions }
    assert_equal "Service is under maintenance. Please try again later.", v1.body
    assert_nil token.retry_after

    proxy = serve { |_, conn| conn.text(502, "<html>Bad gateway</html>", "Content-Type" => "text/html") }
    api = assert_raises(Lingara::ApiError) { proxy.client.list_api_versions }
    assert_equal [502, "http_502"], [api.status, api.code]

    broken = serve { |_, conn| conn.text(500, "") }
    oauth = assert_raises(Lingara::OAuthError) { credentialed(broken).get_usage }
    assert_equal [500, "http_500", nil], [oauth.status, oauth.error, oauth.description]

    [token, v1, api, oauth].each { |error| assert_kind_of Lingara::Error, error }
  end

  # 29.9.26t AC14: D4's order over real failures, and a Timeout.timeout
  # around a call propagating unwrapped.
  def test_transport_failures_map_to_kinds
    garbage = garbage_tls_url
    assert_equal :tls, kind_of_failure(nil) { Lingara::Client.new(base_url: garbage).list_api_versions }
    assert_equal :tls, tls_mid_body_kind

    closed = TCPServer.new("127.0.0.1", 0)
    port = closed.addr[1]
    closed.close
    assert_equal :connect, kind_of_failure(nil) { Lingara::Client.new(base_url: "http://127.0.0.1:#{port}").list_api_versions }

    silent_tls = serve_silently
    assert_equal :connect, kind_of_failure(nil) {
      Lingara::Client.new(base_url: "https://127.0.0.1:#{silent_tls.addr[1]}", net_http_options: {open_timeout: 0.3}).list_api_versions
    }
    assert_equal :connect, Lingara::Transport.kind(Net::OpenTimeout.new, Lingara::Transport::Phase.new(nil))

    silent = serve { |_, _conn| sleep 2 }
    assert_equal :timeout, kind_of_failure(silent) { |s| s.client(net_http_options: {read_timeout: 0.3}).list_api_versions }

    hangup = serve { |_, _conn| nil }
    assert_equal :connect, kind_of_failure(hangup) { |s| s.client.list_api_versions }

    cut = serve do |_, conn|
      conn.write_head(200, {"Content-Type" => "application/json", "Content-Length" => "100"})
      conn.socket.write("{\"versions\":")
      sleep 0.1
      conn.reset
    end
    assert_equal :reset, kind_of_failure(cut) { |s| s.client.list_api_versions }

    assert_raises(Timeout::Error) { Timeout.timeout(0.3) { silent.client.list_api_versions } }
  ensure
    silent_tls&.close
  end

  # 29.9.26t AC22: a response missing a required field is :malformed_response
  # inside the family; an extra field is ignored; an unlisted value of a
  # component enum decodes to unknown_default_open_api when the generated
  # class maps it there, and otherwise is :malformed_response; an unlisted
  # value of an inline enum attribute is refused the same way.
  def test_generated_decode_failures_stay_in_the_family
    body = PLAN
    server = serve do |request, conn|
      (request.path == "/oauth/token") ? Fixtures.token_response(conn) : conn.json(200, body)
    end
    client = credentialed(server)

    body = PLAN.except(:status)
    missing = assert_raises(Lingara::TransportError) { client.get_lesson_plan(PLAN[:id]) }
    assert_equal :malformed_response, missing.kind
    assert_kind_of Lingara::Error, missing

    body = PLAN.merge(surprise: "ignored")
    assert_equal "complete", client.get_lesson_plan(PLAN[:id]).value.status

    body = PLAN.merge(status: "archived_in_a_later_version")
    maps_unknown = begin
      Lingara::PlanStatus.build_from_hash("archived_in_a_later_version") == Lingara::PlanStatus::UNKNOWN_DEFAULT_OPEN_API
    rescue
      false
    end
    if maps_unknown
      assert_equal "unknown_default_open_api", client.get_lesson_plan(PLAN[:id]).value.status
    else
      assert_equal :malformed_response, assert_raises(Lingara::TransportError) { client.get_lesson_plan(PLAN[:id]) }.kind
    end

    inline = assert_raises(Lingara::TransportError) do
      Lingara::Decoding.event(Lingara::GenerateVocabularyEventItem, "not_item", '{"word":"x","translation":"y"}')
    end
    assert_equal :malformed_event, inline.kind
  end

  # D4's carve-out: a TLS peer that closes an open stream without
  # close_notify, before its terminal event, is C2 D6's EOF
  # (:stream_ended_early), whether OpenSSL reports it as an SSLError or an
  # EOFError, and never :tls.
  def test_tls_close_without_close_notify_mid_stream_is_stream_ended_early
    context, store = Fixtures.tls_pair
    server = Fixtures.server(tls: context) do |_, conn|
      conn.sse
      conn.event("started", {meta: {level: 2, source_lang: "en", target_lang: "zh", framework: "HSK", count: 1, ai_generated: true}})
      sleep 0.1
      conn.socket.io.close
    end
    @servers << server
    seen = []
    error = assert_raises(Lingara::TransportError) do
      credentialed(server, net_http_options: {cert_store: store}).generate_vocabulary(level: 2, source_lang: "en", target_lang: "zh") do |event|
        seen << event.event
      end
    end
    assert_equal [:stream_ended_early, ["started"]], [error.kind, seen]
  end

  private

  def kind_of_failure(server)
    yield(server)
    flunk "expected a TransportError"
  rescue Lingara::TransportError => e
    e.kind
  end

  # The base URL of a server that answers a ClientHello with bytes that are
  # not TLS.
  def garbage_tls_url
    tcp = TCPServer.new("127.0.0.1", 0)
    Thread.new do
      socket = tcp.accept
      socket.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
      sleep 0.2
      socket.close
      tcp.close
    end
    "https://127.0.0.1:#{tcp.addr[1]}"
  end

  # A TLS server that sends headers, then raw bytes on the TCP socket under
  # the TLS session, so the client's next record fails to decrypt.
  def tls_mid_body_kind
    context, store = Fixtures.tls_pair
    server = serve(tls: context) do |_, conn|
      conn.write_head(200, {"Content-Type" => "application/json", "Content-Length" => "100"})
      conn.socket.write("{\"versions\":")
      conn.socket.flush
      sleep 0.05
      conn.socket.io.write("this is not a TLS record")
      conn.socket.io.flush
      sleep 0.2
    end
    kind_of_failure(server) { |s| s.client(net_http_options: {cert_store: store}).list_api_versions }
  end

  # Accepts TCP and never speaks, so a TLS handshake stalls.
  def serve_silently
    TCPServer.new("127.0.0.1", 0).tap do |tcp|
      Thread.new do
        sockets = []
        loop { sockets << tcp.accept }
      rescue IOError
        nil
      end
    end
  end
end
