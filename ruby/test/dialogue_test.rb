# frozen_string_literal: true

require "support/fake_server"

class DialogueTest < Minitest::Test
  DEFINITION = {"schema" => "dialogue.v1", "id" => "night_market_keepers", "start" => "greet", "npcs" => [],
                "checkpoints" => [], "facts" => [], "x-layout" => {"greet" => {"x" => 120}}}.freeze
  DIALOGUE = {id: "d-1", name: "Night market", created_at: "2026-10-28T09:00:00Z", updated_at: "2026-10-28T09:00:00Z",
              draft: {revision: 2, draft_version: 4, definition: DEFINITION}, active: nil, warnings: []}.freeze
  IR = '{"id":"night_market_keepers",  "schema":"dialogue.v1"}'
  ETAG = '"3b7e"'

  def page(id, next_cursor, has_more)
    item = {id: id, name: "Night market", ir_id: "night_market_keepers", created_at: "2026-10-28T09:00:00Z",
            updated_at: "2026-10-28T09:00:00Z", has_draft: true, active_revision: nil, npc_count: 1, checkpoint_count: 2}
    {items: [item], next_cursor: next_cursor, has_more: has_more}
  end

  def answer(request, conn)
    case request.path
    when "/v1/dialogues?limit=1&name=market" then conn.json(200, page("d-1", "dl1", true))
    when "/v1/dialogues?cursor=dl1&limit=1&name=market" then conn.json(200, page("d-2", nil, false))
    when "/v1/dialogues/d%2F1/export?revision=draft&layout=omit"
      conn.text(200, IR, {"Content-Type" => "application/json", "ETag" => ETAG, "Lingara-Dialogue-Revision" => "4"})
    when "/v1/dialogues/d%2F1/export"
      conn.text(304, "", {"ETag" => ETAG, "Lingara-Dialogue-Revision" => "4"})
    else conn.json(200, DIALOGUE)
    end
  end

  # 29.10.26c AC21: through client.dialogues, PUT and PATCH reach the server
  # with their JSON bodies and Content-Type application/json; the export's
  # Body carries the bytes unparsed and both headers, its query in the
  # spec's order, and a 304 is NotModified with both headers, If-None-Match
  # sent unchanged; page_dialogues ends on has_more false, its filters
  # resent.
  def test_put_patch_export_and_paging
    server = Fixtures.server { |request, conn| answer(request, conn) }
    dialogues = server.client(**Fixtures.credentials).dialogues

    draft = dialogues.get_dialogue("d-1").value.draft
    dialogues.save_dialogue_draft("d-1", {definition: draft.definition, draft_version: draft.draft_version})
    dialogues.rename_dialogue("d-1", {name: "Renamed"})
    body = dialogues.export_dialogue("d/1", revision: "draft", layout: "omit").value
    assert_equal Lingara::ExportedDialogue::Body.new(revision: 4, etag: ETAG, ir_json: IR), body
    not_modified = dialogues.export_dialogue("d/1", if_none_match: ETAG).value
    assert_equal Lingara::ExportedDialogue::NotModified.new(revision: 4, etag: ETAG), not_modified
    assert_equal %w[d-1 d-2], dialogues.page_dialogues(name: "market", limit: 1).map(&:id)

    sent = server.requests.reject { |r| r.path == "/oauth/token" }
    put, patch, export, revalidate = sent[1], sent[2], sent[3], sent[4]
    assert_equal %w[PUT /v1/dialogues/d-1/draft], [put.method, put.path]
    assert_equal({"definition" => DEFINITION, "draft_version" => 4}, JSON.parse(put.body))
    assert_equal %w[PATCH /v1/dialogues/d-1], [patch.method, patch.path]
    assert_equal({"name" => "Renamed"}, JSON.parse(patch.body))
    [put, patch].each { |r| assert_equal "application/json", r.headers["content-type"] }
    assert_nil export.headers["if-none-match"]
    assert_equal ETAG, revalidate.headers["if-none-match"]
    assert_equal 7, sent.size
  ensure
    server&.close
  end
end
