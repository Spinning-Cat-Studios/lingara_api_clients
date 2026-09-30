# frozen_string_literal: true

require "support/fake_server"

SNIPPETS = File.expand_path("../../snippets/ruby", __dir__)
Dir[File.join(SNIPPETS, "*.rb")].sort.each { |file| require file }

class SnippetsTest < Minitest::Test
  PLAN_ID = "3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37"
  PLAN = {id: PLAN_ID, status: "complete", title: "At the night market", source_lang: "en", target_lang: "zh", level: 2,
          created_at: "2026-09-23T10:00:00Z", ai_generated: true,
          content: {learning_objectives: [], vocabulary: [{word: "多少钱", translation: "how much"}], sets: []}}.freeze
  VERSION = {id: "2026-09-glowing-hoatzin", state: "supported", lts: false, minted_at: "2026-09-01T00:00:00Z", sunset_at: nil,
             summary: nil, history: [], spec: {url: "/v1/openapi.json", sha256: nil}}.freeze

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
    server = Fixtures.server { |request, conn| answer(request, conn) }
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
    end
    %w[你好 ready still 好的 vocab 3.2.0 current supported].each { |text| assert_includes out, text }
    snippet_methods = LingaraSnippets.singleton_methods.sort
    assert_equal 11, snippet_methods.size
  ensure
    server&.close
  end
end
