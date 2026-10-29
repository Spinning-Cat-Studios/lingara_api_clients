# frozen_string_literal: true

require "support/fake_server"

class CanvasTest < Minitest::Test
  FIELDS = {"name" => "Lesson server", "x_editor_hint" => {"collapsed" => true}}.freeze
  DEFINITION = {"schema" => 2, "nodes" => [{"id" => "n1", "kind" => "client", "position" => [0.5, 1.5], "fields" => FIELDS}]}.freeze
  CANVAS = {id: "c-1", name: "Hooks", created_at: "2026-10-20T09:00:00Z", updated_at: "2026-10-20T09:00:00Z",
            draft: {revision: 1, draft_version: 4, definition: DEFINITION, objects: {}}, active: nil}.freeze

  def page(id, next_cursor, has_more)
    item = {id: id, name: "Hooks", recipe: "blank", created_at: "2026-10-20T09:00:00Z", updated_at: "2026-10-20T09:00:00Z",
            has_draft: true, active_revision: nil, node_count: 1, node_kinds: ["client"], scopes: [], objects: []}
    {items: [item], total: nil, next_cursor: next_cursor, has_more: has_more}
  end

  def answer(request, conn)
    case request.path
    when "/v1/canvases?limit=1&kinds=client%2Cwebhook" then conn.json(200, page("c-1", "cv1", true))
    when "/v1/canvases?cursor=cv1&limit=1&kinds=client%2Cwebhook" then conn.json(200, page("c-2", nil, false))
    when "/v1/canvases" then conn.json(200, page("c-3", nil, true))
    else conn.json(200, CANVAS)
    end
  end

  # 16.10.26ak AC14: through client.canvases, PUT and PATCH reach the server
  # with their JSON bodies and Content-Type application/json; a definition
  # read then saved keeps an unknown fields key; page_canvases ends on
  # has_more false with its filters resent, and a page with has_more but no
  # next_cursor raises malformed_response.
  def test_put_patch_and_paging
    server = Fixtures.server { |request, conn| answer(request, conn) }
    canvases = server.client(**Fixtures.credentials).canvases

    draft = canvases.get_canvas("c-1").value.draft
    canvases.save_canvas_draft("c-1", {definition: draft.definition, draft_version: draft.draft_version})
    canvases.rename_canvas("c-1", {name: "Renamed"})
    assert_equal %w[c-1 c-2], canvases.page_canvases(limit: 1, kinds: %w[client webhook]).map(&:id)
    error = assert_raises(Lingara::TransportError) { canvases.page_canvases.to_a }
    assert_equal :malformed_response, error.kind

    sent = server.requests.reject { |r| r.path == "/oauth/token" }
    put, patch = sent[1], sent[2]
    assert_equal %w[PUT /v1/canvases/c-1/draft], [put.method, put.path]
    assert_equal({"definition" => DEFINITION, "draft_version" => 4}, JSON.parse(put.body))
    assert_equal %w[PATCH /v1/canvases/c-1], [patch.method, patch.path]
    assert_equal({"name" => "Renamed"}, JSON.parse(patch.body))
    [put, patch].each { |r| assert_equal "application/json", r.headers["content-type"] }
    assert_equal 6, sent.size
  ensure
    server&.close
  end
end
