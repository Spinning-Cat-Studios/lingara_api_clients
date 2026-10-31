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

  MINTED = "lgr_et_unit0000000000000000000000000000000000000"
  MINT_ANSWER = {token: MINTED, expires_at: "2026-10-01T09:27:44Z", expires_in: 900, subject: "lgr_sub_unit",
                 scopes: ["embed:play"], account_linked: false}.freeze

  # 1.10.26w AC19: inspect, to_s and pp of a MintedToken (and of the
  # Response holding it) show [REDACTED], never the lgr_et_ value, while
  # token.expose_secret returns it; a fixture missing subject is refused as
  # malformed_response, and that error renders nothing of the body.
  def test_a_minted_token_renders_redacted
    minted = Lingara::MintedToken.decode(JSON.generate(MINT_ANSWER))
    assert_equal MINTED, minted.token.expose_secret
    assert_equal [Time.utc(2026, 10, 1, 9, 27, 44), 900, "lgr_sub_unit", ["embed:play"], false],
      [minted.expires_at, minted.expires_in, minted.subject, minted.scopes, minted.account_linked]
    [minted, Lingara::Response.new(minted, nil)].each do |holder|
      renderings(holder).each do |text|
        refute_includes text, MINTED
        assert_includes text, "[REDACTED]"
      end
    end

    error = assert_raises(Lingara::TransportError) { Lingara::MintedToken.decode(JSON.generate(MINT_ANSWER.except(:subject))) }
    assert_equal :malformed_response, error.kind
    assert_nil error.cause
    renderings(error).each { |text| refute_includes text, MINTED }
    wrong = MINT_ANSWER.merge(token: "lgr_at_notminted")
    assert_raises(Lingara::TransportError) { Lingara::MintedToken.decode(JSON.generate(wrong)) }
  end

  # 31.10.26g AC6: a well-formed notice is copied field for field, nils
  # kept, and a message of exactly 280 astral code points is kept; none,
  # absent or null, is nil; a malformed notice is nil and the token is still
  # minted; a malformed token field is still malformed_response; inspect
  # shows the notice with the token redacted.
  def test_minted_token_update_available_is_copied_or_dropped
    smileys = "\u{1F600}" * 280
    mint = ->(notice) { Lingara::MintedToken.decode(JSON.generate(MINT_ANSWER.merge(update_available: notice))) }
    [{latest: nil, deprecated: true, message: "Use 1.2.0"}, {latest: "1.2.0", deprecated: false, message: smileys}].each do |notice|
      minted = mint.call(notice)
      got = minted.update_available
      assert_equal [notice[:latest], notice[:deprecated], notice[:message]], [got.latest, got.deprecated, got.message]
      assert_includes minted.inspect, "update_available={"
      assert_includes minted.inspect, "[REDACTED]"
      refute_includes minted.inspect, MINTED
    end
    assert_nil Lingara::MintedToken.decode(JSON.generate(MINT_ANSWER)).update_available
    assert_nil mint.call(nil).update_available
    [5, {latest: "1.2.0", message: nil}, {latest: 7, deprecated: true, message: nil},
      {latest: nil, deprecated: true, message: "#{smileys}x"}].each do |notice|
      minted = mint.call(notice)
      assert_nil minted.update_available, notice.inspect
      assert_equal MINTED, minted.token.expose_secret
    end
    error = assert_raises(Lingara::TransportError) { Lingara::MintedToken.decode(JSON.generate(MINT_ANSWER.merge(expires_in: "soon"))) }
    assert_equal :malformed_response, error.kind
  end
end
