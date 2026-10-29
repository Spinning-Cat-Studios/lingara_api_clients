# frozen_string_literal: true

module Lingara
  # What export_dialogue answers, as a Response's value (ADR 29.10.26c D6):
  # a Body, the dialogue.v1 document as text never parsed here (its bytes
  # are canonical and etag is their SHA-256, so store them as received), or
  # on a 304 a NotModified: keep the copy you hold. Both carry the revision
  # (Lingara-Dialogue-Revision) and the etag (ETag) exactly as sent.
  module ExportedDialogue
    Body = Data.define(:revision, :etag, :ir_json)
    NotModified = Data.define(:revision, :etag)

    # The arm a 200 or 304 answers. A missing ETag or
    # Lingara-Dialogue-Revision, or a revision that is not a non-negative
    # integer, is TransportError :malformed_response.
    def self.from(response)
      revision = response["Lingara-Dialogue-Revision"].to_s
      etag = response["ETag"]
      unless revision.match?(/\A\d+\z/) && etag
        raise TransportError.new(:malformed_response, "an export needs ETag and an integer Lingara-Dialogue-Revision")
      end
      return NotModified.new(revision: revision.to_i, etag: etag) if response.code.to_i == 304
      Body.new(revision: revision.to_i, etag: etag, ir_json: response.body.to_s.dup.force_encoding(Encoding::UTF_8))
    end
  end

  # The stored-dialogue operations (ADR 29.10.26c; scopes dialogues:read and
  # dialogues:write), reached as client.dialogues. The group holds no state
  # of its own: every call goes through the client's request path, so the
  # token, retries, version pin and errors are the client's. Each call
  # returns a Response.
  #
  # A stale draft_version is 409 dialogue_conflict, raised as an ApiError and
  # never retried: re-read with get_dialogue, take draft.draft_version (or
  # send 0 when draft is nil), and decide. A replayed activate_dialogue
  # answers 409 dialogue_conflict too, and writes nothing. Branch on an
  # error's code, never its localised message. There is no delete: a
  # dialogue is deleted through the platform's lifecycle routes, not this
  # library.
  class Dialogues
    # Built by Client#dialogues over the client's own request path, and its
    # export path, which reads a 304 as an answer.
    def initialize(request, export)
      @request = request
      @export = export
    end

    # One page of dialogues, a DialoguePage; page_dialogues walks them.
    def list_dialogues(cursor: nil, limit: nil, name: nil)
      @request.call("listDialogues", [], {cursor: cursor, limit: limit, name: name}, nil)
    end

    # Creates a dialogue (201): body is {name:, definition:}. Its warnings
    # are advice, never a refusal.
    def create_dialogue(body)
      @request.call("createDialogue", [], nil, body)
    end

    # One dialogue, its draft and active revision inline.
    def get_dialogue(dialogue_id)
      @request.call("getDialogue", [dialogue_id], nil, nil)
    end

    # Renames a dialogue (PATCH): body is {name:}.
    def rename_dialogue(dialogue_id, body)
      @request.call("renameDialogue", [dialogue_id], nil, body)
    end

    # Saves the draft (PUT): body is {definition:, draft_version:, name:};
    # draft_version 0 with no draft opens one. A stale draft_version is 409
    # dialogue_conflict, which may follow your own retried save: re-read
    # with get_dialogue and compare first.
    def save_dialogue_draft(dialogue_id, body)
      @request.call("saveDialogueDraft", [dialogue_id], nil, body)
    end

    # Promotes the draft: body is {draft_version:}. A stale or missing draft
    # is 409 dialogue_conflict and writes nothing.
    def activate_dialogue(dialogue_id, body)
      @request.call("activateDialogue", [dialogue_id], nil, body)
    end

    # Exports a dialogue (dialogues:read): a Response whose value is an
    # ExportedDialogue::Body or, when +if_none_match+ (the etag you hold,
    # sent back unchanged) still matches, an ExportedDialogue::NotModified.
    # +revision+ is "active" or "draft", +layout+ "keep" or "omit"; each is
    # sent only when given. A 304 still spends one unit of the rate budget.
    # K4's retries apply.
    def export_dialogue(dialogue_id, revision: nil, layout: nil, if_none_match: nil)
      headers = if_none_match.nil? ? {} : {"If-None-Match" => if_none_match}
      @export.call([dialogue_id], {revision: revision, layout: layout}, headers)
    end

    # Every DialogueSummary the filters (name:, limit:) match, following
    # next_cursor until a page says has_more false, the filters resent on
    # every page: an Enumerator that fetches lazily, or with a block, each
    # item yielded. It never sleeps or polls and stops on the first error. A
    # page with has_more and no next_cursor is TransportError
    # :malformed_response, its items unyielded.
    def page_dialogues(**filters, &block)
      items = Enumerator.new { |out| each_item(filters, out) }
      block ? items.each(&block) : items
    end

    private

    def each_item(filters, out)
      cursor = nil
      loop do
        page = list_dialogues(**filters, cursor: cursor).value
        if page.has_more && page.next_cursor.nil?
          raise TransportError.new(:malformed_response, "a dialogue page has more but no next_cursor")
        end
        page.items.each { |item| out << item }
        break unless page.has_more
        cursor = page.next_cursor
      end
    end
  end

  class Client
    # The stored-dialogue operations (ADR 29.10.26c), over this client's
    # request path.
    def dialogues
      @dialogues ||= Dialogues.new(
        lambda { |id, args, query, body| json(id, args, query: query, body: payload(OPERATIONS.fetch(id), body)) },
        lambda { |args, query, headers| export_dialogue_response(args, query, headers) }
      )
    end

    private

    # exportDialogue read by hand (D6): a 304 is an answer, the body is
    # never decoded, and both headers are part of the result.
    def export_dialogue_response(args, query, headers)
      operation = OPERATIONS.fetch("exportDialogue")
      pipeline(operation, url_for(operation, args, query), nil, headers: headers, allow: 304) do |response, _phase, observe|
        served = observe.call(response)
        Response.new(value: ExportedDialogue.from(response), served_version: served)
      end
    end
  end
end
