# frozen_string_literal: true

require "pp"
require "support/fake_server"

class RedactionTest < Minitest::Test
  TOKEN = "lgr_at_redactme"

  def renderings(object)
    out = [object.inspect, object.to_s, PP.pp(object, +"")]
    out.push(object.message, object.full_message(highlight: false)) if object.is_a?(Exception)
    out.concat(renderings(object.cause)) if object.is_a?(Exception) && object.cause
    out
  end

  def assert_clean(object)
    renderings(object).each do |text|
      refute_includes text, Fixtures::SECRET
      refute_includes text, TOKEN
    end
  end

  # 29.9.26t AC12: inspect, to_s, pp, message and full_message of the Client,
  # the ClientCredentials, an AccessToken and each error never show the
  # secret or the token; the three holders show [REDACTED], and client_id is
  # rendered.
  def test_secret_and_token_redacted_everywhere
    server = Fixtures.server(token: TOKEN) do |request, conn|
      if request.path == "/v1/usage"
        conn.json(403, {code: "insufficient_scope", error: "This call needs the usage:read scope."})
      else
        conn.reset
      end
    end
    client = server.client(**Fixtures.credentials)
    source = client.token_source
    token = source.token
    assert_equal TOKEN, token.expose_secret
    assert_equal Fixtures::SECRET, source.expose_secret

    [client, source, token].each do |holder|
      assert_clean(holder)
      renderings(holder).each { |text| assert_includes text, "[REDACTED]" }
    end
    [client, source].each { |holder| assert_includes holder.inspect, Fixtures::CLIENT_ID }

    api = assert_raises(Lingara::ApiError) { client.get_usage }
    transport = assert_raises(Lingara::TransportError) { client.list_api_versions }
    oauth = Lingara::OAuthError.new(status: 401, error: "invalid_client", description: "unknown client")
    scrubbed = begin
      Lingara::Transport.new(net_http_options: {}).send(:raise_transport, :reset, IOError.new("echoed Bearer #{TOKEN}"), [TOKEN])
    rescue Lingara::TransportError => e
      e
    end
    assert_kind_of Lingara::ScrubbedCause, scrubbed.cause
    [api, transport, oauth, scrubbed, Lingara::MaintenanceError.new(body: "down")].each { |error| assert_clean(error) }
  ensure
    server&.close
  end
end
