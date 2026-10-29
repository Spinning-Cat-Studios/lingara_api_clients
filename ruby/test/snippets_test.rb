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

  MINTED = {token: "lgr_et_unit0000000000000000000000000000000000000", expires_at: "2026-10-01T09:27:44Z", expires_in: 900,
            subject: "lgr_sub_unit", scopes: ["embed:play", "events:write"], account_linked: false}.freeze

  LINK = {status: "linked", subject: "lgr_sub_account", previous_subject: "lgr_sub_unit", linked_at: "2026-11-02T18:04:11Z",
          scopes: ["embed:play"], carried: {}, kept: {}}.freeze

  # The answer to each embed route a snippet calls (ADR 1.10.26w; the link,
  # ADR 22.10.26t: a PUT answers the link, a DELETE ends it with a 204).
  def answer_embed(request, conn)
    return ((request.method == "PUT") ? conn.json(200, LINK) : conn.text(204, "")) if request.path.end_with?("/link")
    case request.path
    when "/v1/embed/tokens" then conn.json(200, MINTED)
    when "/v1/embed/dialogue/turns"
      conn.sse
      conn.event("delta", {text: "十块钱，"})
      conn.event("delta", {text: "一盘。"})
      conn.event("done", {})
    else conn.text(204, "")
    end
  end

  CANVAS_ID = "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b"
  DEFINITION = {schema: 2, nodes: [{id: "n1", kind: "client", position: [0.5, 1.5], fields: {name: "Lessons"}}], edges: []}.freeze
  CANVAS = {id: CANVAS_ID, name: "Lesson plan webhook", created_at: "2026-10-20T09:00:00Z", updated_at: "2026-10-20T09:00:00Z",
            draft: {revision: 1, draft_version: 4, definition: DEFINITION, objects: {}},
            active: {revision: 1, definition: DEFINITION, objects: {}, activated_at: "2026-10-20T09:00:00Z"}}.freeze
  SUMMARY = {id: CANVAS_ID, name: "Lesson plan webhook", recipe: "lesson_plan_ready", created_at: "2026-10-20T09:00:00Z",
             updated_at: "2026-10-20T09:00:00Z", has_draft: true, active_revision: 1, node_count: 2,
             node_kinds: ["client", "webhook"], scopes: ["events:read"], objects: []}.freeze

  # The answer to each canvas route a snippet calls (ADR 16.10.26ak): the
  # second draft save is a 409 canvas_conflict, so the snippet's handling
  # runs too.
  def answer_canvases(request, conn)
    path = request.path.split("?").first
    if path == "/v1/canvases" && request.method == "GET"
      conn.json(200, {items: [SUMMARY], total: 1, next_cursor: nil, has_more: false})
    elsif path == "/v1/canvases/dry-run"
      conn.json(200, {ok: false, verdicts: [{handle: "c1", kind: "client", op: "create", verdict: "refused", code: "client_limit_reached",
                                             error: "Too many clients."}]})
    elsif path.end_with?("/draft") && (@saves = (@saves || 0) + 1) > 1
      conn.json(409, {code: "canvas_conflict", error: "This canvas changed."})
    else
      conn.json((path == "/v1/canvases") ? 201 : 200, CANVAS)
    end
  end

  def run_canvas_snippets(client)
    LingaraSnippets.list_canvases(client)
    LingaraSnippets.page_canvases(client)
    id = LingaraSnippets.create_canvas(client)
    LingaraSnippets.get_canvas(client, id)
    LingaraSnippets.rename_canvas(client, id)
    2.times { LingaraSnippets.save_canvas_draft(client, id) }
    LingaraSnippets.activate_canvas(client, id)
    LingaraSnippets.dry_run_canvas_plan(client)
  end

  DIALOGUE_ID = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71"
  IR = {schema: "dialogue.v1", id: "night_market_keepers", start: "greet", npcs: [], checkpoints: [], facts: []}.freeze
  DIALOGUE = {id: DIALOGUE_ID, name: "Night market", created_at: "2026-10-28T09:00:00Z", updated_at: "2026-10-28T09:00:00Z",
              draft: {revision: 2, draft_version: 4, definition: IR},
              active: {revision: 1, definition: IR, activated_at: "2026-10-28T09:00:00Z"},
              warnings: [{code: "checkpoint_unreachable", path: "/checkpoints/0"}]}.freeze
  DIALOGUE_ITEM = {id: DIALOGUE_ID, name: "Night market", ir_id: "night_market_keepers", created_at: "2026-10-28T09:00:00Z",
                   updated_at: "2026-10-28T09:00:00Z", has_draft: true, active_revision: nil, npc_count: 1, checkpoint_count: 2}.freeze
  EXPORT_HEADERS = {"ETag" => '"3b7e"', "Lingara-Dialogue-Revision" => "1"}.freeze

  # The answer to each dialogue route a snippet calls (ADR 29.10.26c): the
  # second draft save is a 409 dialogue_conflict, and an export sent with
  # If-None-Match is a 304, so both arms of each snippet run.
  def answer_dialogues(request, conn)
    path = request.path.split("?").first
    if path == "/v1/dialogues" && request.method == "GET"
      conn.json(200, {items: [DIALOGUE_ITEM], next_cursor: nil, has_more: false})
    elsif path.end_with?("/export")
      return conn.text(304, "", EXPORT_HEADERS) if request.headers["if-none-match"]
      conn.text(200, JSON.generate(IR), {"Content-Type" => "application/json"}.merge(EXPORT_HEADERS))
    elsif path.end_with?("/draft") && (@dialogue_saves = (@dialogue_saves || 0) + 1) > 1
      conn.json(409, {code: "dialogue_conflict", error: "This dialogue changed."})
    else
      conn.json((path == "/v1/dialogues") ? 201 : 200, DIALOGUE)
    end
  end

  def run_dialogue_snippets(client)
    LingaraSnippets.list_dialogues(client)
    LingaraSnippets.page_dialogues(client)
    id = LingaraSnippets.create_dialogue(client)
    LingaraSnippets.get_dialogue(client, id)
    LingaraSnippets.rename_dialogue(client, id)
    2.times { LingaraSnippets.save_dialogue_draft(client, id) }
    LingaraSnippets.activate_dialogue(client, id)
    cached = LingaraSnippets.export_dialogue(client, id)
    assert_equal JSON.generate(IR), cached[:ir_json]
    LingaraSnippets.export_dialogue(client, id, cached)
  end

  # The answer to each route a snippet calls.
  def answer(request, conn)
    return answer_embed(request, conn) if request.path.start_with?("/v1/embed/")
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
      next answer_canvases(request, conn) if request.path.start_with?("/v1/canvases")
      next answer_dialogues(request, conn) if request.path.start_with?("/v1/dialogues")
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
      assert_equal MINTED[:token], LingaraSnippets.create_embed_token(client)[:token]
      LingaraSnippets.delete_embed_player(client)
      LingaraSnippets.link_embed_player(LingaraSnippets.delegated_client("lgr_at_delegated", base_url: server.url))
      LingaraSnippets.unlink_embed_player(client)
      assert_equal "十块钱，一盘。", LingaraSnippets.send_dialogue_turn(client).last[:text]
      run_canvas_snippets(client)
      run_dialogue_snippets(client)
    end
    %w[你好 ready still 好的 vocab 3.2.0 current supported c1 lgr_evt_unit2 on\ its\ way lgr_sub_unit deleted 十块钱
      linked\ as\ lgr_sub_account unlinked lesson_plan_ready 2\ node(s) draft_version\ 4 active\ revision\ 1
      renamed\ to saved\ at someone\ saved\ first client_limit_reached night_market_keepers never\ activated
      checkpoint_unreachable changed\ elsewhere is\ new is\ unchanged].each do |text|
      assert_includes out, text
    end
    assert_includes server.requests.map(&:path), "/v1/embed/players/guild-42%2Fplayer-1001"
    link = server.requests.find { |r| r.method == "PUT" }
    assert_equal "Bearer lgr_at_delegated", link.headers["authorization"]
    snippet_methods = LingaraSnippets.singleton_methods.sort
    assert_equal 37, snippet_methods.size
  ensure
    server&.close
  end
end
