# frozen_string_literal: true

require "stringio"
require "support/fake_server"

SNIPPETS = File.expand_path("../../snippets/ruby", __dir__)
Dir[File.join(SNIPPETS, "*.rb")].sort.each { |file| require file }

class SnippetsTest < Minitest::Test
  PLAN_ID = "3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37"
  PLAN = {id: PLAN_ID, status: "complete", title: "At the night market", source_lang: "en", target_lang: "zh", level: 2,
          created_at: "2026-09-23T10:00:00Z", ai_generated: true,
          content: {learning_objectives: [], vocabulary: [{word: "多少钱", translation: "how much"}], sets: []}}.freeze
  VERSION = {id: "2026-09-glowing-hoatzin", state: "supported", lts: false, minted_at: "2026-09-01T00:00:00Z", sunset_at: nil,
             summary: nil, history: [], spec: {url: "/v1/openapi.json", sha256: nil},
             asyncapi: {url: "/v1/asyncapi.json", sha256: nil}}.freeze

  EVENT = {id: "lgr_evt_unit1", type: "lesson_plan.ready", created_at: "2026-10-01T09:12:44Z", api_version: "2026-09-equipped-boxfish",
           subject: "lgr_sub_unit", data: {plan_id: PLAN_ID, status: "complete", title: "At the night market", source_lang: "en",
                                           target_lang: "zh", level: 2}}.freeze
  WEBHOOK_SECRET = "lgr_whsec_#{["unit-webhook-secret-000000000001"].pack("m0")}"

  # The answer to each events route a snippet calls (ADR 30.9.26aa).
  def answer_events(request, conn)
    case [request.method, request.path.split("?").first]
    when ["GET", "/v1/events"] then conn.json(200, {items: [EVENT], next_cursor: "c1", has_more: false})
    when ["POST", "/v1/events"]
      conn.json(202, {id: "lgr_evt_unit2", type: "world.context_changed", created_at: "2026-10-01T09:12:44Z",
                      reaction: {status: "started", plan_id: PLAN_ID, plan_status: "generating"}})
    else
      conn.sse
      conn.chunk("id: c1\nevent: event\ndata: #{JSON.generate(EVENT)}\n\n")
      conn.closed_by_peer?(2)
    end
  end

  # A Rack env carrying EVENT, signed now with WEBHOOK_SECRET.
  def signed_env
    body = JSON.generate(EVENT)
    timestamp = Time.now.to_i.to_s
    mac = OpenSSL::HMAC.digest("SHA256", "unit-webhook-secret-000000000001", "#{EVENT[:id]}.#{timestamp}.#{body}")
    {"rack.input" => StringIO.new(body), "HTTP_WEBHOOK_ID" => EVENT[:id], "HTTP_WEBHOOK_TIMESTAMP" => timestamp,
     "HTTP_WEBHOOK_SIGNATURE" => "v1,#{[mac].pack("m0")}"}
  end

  # The answer to each route a snippet calls.
  def answer(request, conn)
    case request.path
    when "/v1/vocab/stream"
      conn.sse
      conn.event("item", {word: "你好", translation: "hello"})
      conn.event("done", {})
    when "/v1/lesson-plans"
      conn.sse
      conn.event("started", {plan_id: PLAN_ID})
      conn.event("result", {plan: PLAN})
    when "/v1/lesson-plans/#{PLAN_ID}/stream"
      conn.sse
      conn.event("pending", {plan_id: PLAN_ID, status: "generating"})
    when "/v1/tutor/message"
      conn.sse
      conn.event("delta", {text: "好的"})
      conn.event("done", {})
    when "/v1/lesson-plans/#{PLAN_ID}" then conn.json(200, PLAN)
    when "/v1/usage" then conn.json(200, {allowance: [{feature: "vocab", window: "daily", limit: 50, used: 1, remaining: 49}]})
    when "/v1/openapi.json" then conn.json(200, {openapi: "3.2.0"})
    when "/v1/versions" then conn.json(200, {current: VERSION[:id], development: nil, versions: [VERSION.except(:summary, :history, :spec)]})
    else conn.json(200, VERSION)
    end
  end

  # 29.9.26t AC31: every snippet method runs against the fake with no error.
  def test_every_snippet_runs_against_the_fake
    server = Fixtures.server do |request, conn|
      request.path.start_with?("/v1/events") ? answer_events(request, conn) : answer(request, conn)
    end
    client = server.client(**Fixtures.credentials)
    public_client = server.client
    assert_kind_of Lingara::Client, LingaraSnippets.auth(Fixtures::CLIENT_ID, Fixtures::SECRET)
    out, = capture_io do
      LingaraSnippets.generate_vocabulary(client)
      LingaraSnippets.create_lesson_plan(client)
      LingaraSnippets.get_lesson_plan(client, PLAN_ID)
      LingaraSnippets.stream_lesson_plan(client, PLAN_ID)
      LingaraSnippets.send_tutor_message(client)
      LingaraSnippets.get_usage(client)
      LingaraSnippets.errors(client)
      LingaraSnippets.get_open_api_document(public_client)
      LingaraSnippets.list_api_versions(public_client)
      LingaraSnippets.get_api_version(public_client, VERSION[:id])
      LingaraSnippets.list_events(client, nil)
      LingaraSnippets.stream_events(client, "c0")
      LingaraSnippets.send_event(client)
      assert_equal 204, LingaraSnippets.verify_webhook(WEBHOOK_SECRET, signed_env).first
    end
    %w[你好 ready still 好的 vocab 3.2.0 current supported c1 lgr_evt_unit2 on\ its\ way].each { |text| assert_includes out, text }
    snippet_methods = LingaraSnippets.singleton_methods.sort
    assert_equal 15, snippet_methods.size
  ensure
    server&.close
  end
end
