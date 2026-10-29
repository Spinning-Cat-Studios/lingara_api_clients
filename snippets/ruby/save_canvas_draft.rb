# frozen_string_literal: true

module LingaraSnippets
  def self.save_canvas_draft(client, canvas_id)
    # lingara:begin saveCanvasDraft
    # Read, change, save at the draft_version you read. A stale version is
    # 409 canvas_conflict: never retried, and never merged for you. Re-read,
    # compare the draft there with the one you sent (after a retried save it
    # may be your own), and decide.
    draft = client.canvases.get_canvas(canvas_id).value.draft
    definition = draft.definition.to_hash
    definition[:nodes].first[:fields] = (definition[:nodes].first[:fields] || {}).merge("name" => "Lesson server")
    begin
      saved = client.canvases.save_canvas_draft(canvas_id, {definition: definition, draft_version: draft.draft_version}).value
      puts "saved at draft_version #{saved.draft.draft_version}"
    rescue Lingara::ApiError => e
      raise unless e.code == "canvas_conflict"
      current = client.canvases.get_canvas(canvas_id).value.draft
      puts "someone saved first: the draft is now at version #{current.draft_version}"
    end
    # lingara:end
  end
end
