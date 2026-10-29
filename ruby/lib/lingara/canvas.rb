# frozen_string_literal: true

module Lingara
  # The canvas operations (ADR 16.10.26ak; scopes canvas:read and
  # canvas:write), reached as client.canvases. The group holds no state of
  # its own: every call goes through the client's request path, so the
  # token, retries, version pin and errors are the client's. Each call
  # returns a Response.
  #
  # A stale draft_version is 409 canvas_conflict, raised as an ApiError and
  # never retried: re-read with get_canvas, compare its draft with yours
  # (after a retried save it may be your own), and decide. A replayed
  # activate_canvas answers 409 canvas_conflict too, and writes nothing.
  # Branch on an error's code, never its localised message. A dry-run
  # answers 200 whether or not ok is true; a refused verdict's code and
  # English error are data. There is no delete: a canvas is deleted through
  # the platform's lifecycle routes, not this library.
  class Canvases
    # Built by Client#canvases over the client's own request path.
    def initialize(request)
      @request = request
    end

    # One page of canvases, a CanvasPage; page_canvases walks them. The
    # lists are sent comma-separated, one value each.
    def list_canvases(cursor: nil, limit: nil, name: nil, status: nil, recipes: nil, kinds: nil, scopes: nil)
      lists = {recipes: recipes, kinds: kinds, scopes: scopes}.transform_values { |list| list unless list.nil? || list.empty? }
      @request.call("listCanvases", [], {cursor: cursor, limit: limit, name: name, status: status, **lists}, nil)
    end

    # Creates a canvas (201). The answer does not echo recipe.
    def create_canvas(body)
      @request.call("createCanvas", [], nil, body)
    end

    # One canvas, its draft and active revision inline.
    def get_canvas(canvas_id)
      @request.call("getCanvas", [canvas_id], nil, nil)
    end

    # Renames a canvas (PATCH): body is {name:}.
    def rename_canvas(canvas_id, body)
      @request.call("renameCanvas", [canvas_id], nil, body)
    end

    # Saves the draft (PUT): body is {definition:, draft_version:, name:}.
    # A stale draft_version is 409 canvas_conflict, which may follow your
    # own retried save: re-read with get_canvas and compare first.
    def save_canvas_draft(canvas_id, body)
      @request.call("saveCanvasDraft", [canvas_id], nil, body)
    end

    # Promotes a draft with the objects an apply made: body is
    # {draft_version:, outcome:, objects:}.
    def activate_canvas(canvas_id, body)
      @request.call("activateCanvas", [canvas_id], nil, body)
    end

    # Judges a plan ({ops:}) without applying it. A plan with a refused op
    # still answers 200, ok false.
    def dry_run_canvas_plan(body)
      @request.call("dryRunCanvasPlan", [], nil, body)
    end

    # Every CanvasSummary the filters match, following next_cursor until a
    # page says has_more false, the filters resent on every page: an
    # Enumerator that fetches lazily, or with a block, each item yielded. It
    # never sleeps or polls and stops on the first error. A page with
    # has_more and no next_cursor is TransportError :malformed_response,
    # its items unyielded.
    def page_canvases(**filters, &block)
      items = Enumerator.new { |out| each_item(filters, out) }
      block ? items.each(&block) : items
    end

    private

    def each_item(filters, out)
      cursor = nil
      loop do
        page = list_canvases(**filters, cursor: cursor).value
        if page.has_more && page.next_cursor.nil?
          raise TransportError.new(:malformed_response, "a canvas page has more but no next_cursor")
        end
        page.items.each { |item| out << item }
        break unless page.has_more
        cursor = page.next_cursor
      end
    end
  end

  class Client
    # The canvas operations (ADR 16.10.26ak), over this client's request
    # path.
    def canvases
      @canvases ||= Canvases.new(lambda do |id, args, query, body|
        json(id, args, query: query, body: payload(OPERATIONS.fetch(id), body))
      end)
    end
  end
end
