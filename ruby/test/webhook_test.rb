# frozen_string_literal: true

require "json"
require "pp"
require "stringio"
require "support/fake_server"

class WebhookTest < Minitest::Test
  VECTORS = File.expand_path("../../conformance/vectors/webhook-signatures.json", __dir__)

  def vectors
    JSON.parse(File.read(VECTORS)).fetch("vectors")
  end

  def webhook(vector)
    Lingara::Events::Webhook.new(vector["secrets"], clock: -> { Time.at(vector["now"]) })
  end

  # The reason a call raises, or nil when it returns.
  def reason
    yield
    nil
  rescue Lingara::Events::VerificationError => e
    e.reason.to_s
  end

  # 30.9.26aa AC28: every D5 vector gives its expected result through
  # verify: the event's id and type (UnknownEvent when `unknown`), the
  # error's reason, or a refused construction. 30.9.26aa AC45: through
  # verify_signature, every ok and malformed_payload vector passes and every
  # other error vector raises its reason.
  def test_every_shared_vector_verifies_as_expected
    all = vectors
    assert_operator all.size, :>=, 28
    all.each do |vector|
      name = vector["name"]
      expect = vector["expect"]
      if expect["refused"]
        assert_raises(ArgumentError, name) { webhook(vector) }
        next
      end
      hook = webhook(vector)
      if (ok = expect["ok"])
        event = hook.verify(vector["body"], vector["headers"])
        assert_equal [ok["id"], ok["type"]], [event.id, event.type], name
        assert_equal !!ok["unknown"], event.is_a?(Lingara::Events::UnknownEvent), name
        assert_kind_of Lingara::Events::Event, event, name
      else
        assert_equal expect["error"], reason { hook.verify(vector["body"], vector["headers"]) }, name
      end
      got = reason { assert_nil hook.verify_signature(vector["body"], vector["headers"]) }
      if expect["ok"] || expect["error"] == "malformed_payload"
        assert_nil got, "#{name} (verify_signature)"
      else
        assert_equal expect["error"], got, "#{name} (verify_signature)"
      end
    end
  end

  # 30.9.26aa AC30: a Rack env, whose headers are HTTP_WEBHOOK_ID and its
  # siblings beside everything else Rack puts there, verifies exactly as a
  # plain header Hash does.
  def test_a_rack_env_is_accepted_as_headers
    vector = vectors.find { |v| v["name"] == "valid-single-signature" }
    headers = vector["headers"]
    env = {"REQUEST_METHOD" => "POST", "PATH_INFO" => "/webhooks/lingara", "CONTENT_TYPE" => "application/json",
           "rack.input" => StringIO.new(vector["body"]), "HTTP_WEBHOOK_ID" => headers["webhook-id"],
           "HTTP_WEBHOOK_TIMESTAMP" => headers["webhook-timestamp"], "HTTP_WEBHOOK_SIGNATURE" => headers["webhook-signature"]}
    hook = webhook(vector)
    assert_equal hook.verify(vector["body"], headers), hook.verify(env["rack.input"].read, env)
    assert_equal "missing_header", reason { hook.verify(vector["body"], env.except("HTTP_WEBHOOK_SIGNATURE")) }
  end

  def test_the_error_is_outside_lingara_error_and_secrets_never_render
    refute_operator Lingara::Events::VerificationError, :<, Lingara::Error
    secret = "lgr_whsec_Y29uZm9ybWFuY2Utd2ViaG9vay1zZWNyZXQtMDAwMSE="
    hook = Lingara::Events::Webhook.new(secret)
    [hook.inspect, hook.to_s, PP.pp(hook, +"")].each { |text| refute_includes text, "Y29uZm9ybWFuY2" }
    error = assert_raises(ArgumentError) { Lingara::Events::Webhook.new("whsec_Y29uZm9ybWFuY2Utd2ViaG9vay1zZWNyZXQtMDAwMSE=") }
    refute_includes error.message, "Y29uZm9ybWFuY2"
  end
end
