# frozen_string_literal: true

require "support/fake_server"

class ClientTest < Minitest::Test
  VERSIONS = {current: "2026-09-glowing-hoatzin", development: nil, versions: []}.freeze

  # 29.9.26t AC27: a JSON method's Response carries served_version from the
  # echo, and a credential-free client calls a public operation with no
  # exchange and no Authorization header.
  def test_served_version_and_credential_free_client
    server = FakeServer.new do |_, conn|
      conn.json(200, VERSIONS, {"Lingara-Version" => Lingara::GENERATED_FOR_VERSION})
    end
    response = server.client.list_api_versions
    assert_equal Lingara::GENERATED_FOR_VERSION, response.served_version
    assert_equal "2026-09-glowing-hoatzin", response.value.current
    assert_equal ["/v1/versions"], server.requests.map(&:path)
    assert_nil server.requests.first.headers["authorization"]
    assert_nil server.requests.first.headers["lingara-version"]
  ensure
    server&.close
  end

  # 29.9.26t AC28: the client's operation methods and operations.rb's keys
  # name the same operations under D3's snake_case rule, both ways. The
  # feed and tail helpers (ADR 30.9.26aa D6, D7) are not operations.
  def test_operation_methods_match_generated_operations
    snake = ->(id) { id.gsub(/([a-z\d])([A-Z])/, '\1_\2').downcase }
    methods = Lingara::Client.public_instance_methods(false) - %i[inspect to_s token_source pretty_print events tail_events]
    assert_equal Lingara::OPERATIONS.keys.map { |id| snake.call(id).to_sym }.sort, methods.sort
    assert_equal 13, methods.size
  end

  # 29.9.26t AC29: Client.new refuses version: "", client_id: without
  # client_secret:, and token_source: beside any credential keyword.
  def test_new_refuses_conflicting_options
    assert_raises(ArgumentError) { Lingara::Client.new(version: "") }
    assert_raises(ArgumentError) { Lingara::Client.new(client_id: Fixtures::CLIENT_ID) }
    assert_raises(ArgumentError) { Lingara::Client.new(client_secret: Fixtures::SECRET) }
    source = Object.new
    %i[client_id client_secret auth scopes].each do |keyword|
      value = {client_id: "x", client_secret: "y", auth: :post, scopes: ["usage:read"]}[keyword]
      options = {keyword => value, :token_source => source}
      assert_raises(ArgumentError, keyword.to_s) { Lingara::Client.new(**options) }
    end
    assert Lingara::Client.new(token_source: source)
    assert Lingara::Client.new(version: "2026-09-glowing-hoatzin")
  end

  def test_a_bad_request_keyword_is_refused_before_any_request
    server = Fixtures.server { |_, conn| conn.json(200, {}) }
    client = server.client(**Fixtures.credentials)
    assert_raises(ArgumentError) { client.generate_vocabulary(level: 2, source_lang: "en", target_lang: "zh", colour: "red") { nil } }
    assert_raises(ArgumentError) { client.generate_vocabulary(source_lang: "en", target_lang: "zh") { nil } }
    assert_empty server.requests
  ensure
    server&.close
  end
end
