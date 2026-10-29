# frozen_string_literal: true

module LingaraSnippets
  def self.activate_canvas(client, canvas_id)
    # lingara:begin activateCanvas
    # After your apply made the draft's objects, promote the draft with the
    # object each node handle became. A replay at a draft_version already
    # promoted is 409 canvas_conflict and writes nothing.
    objects = {"n1" => {id: "0b9e4c2d-7a1f-4e3b-9d8c-5f6a7b8c9d0e"}}
    canvas = client.canvases.activate_canvas(canvas_id, {draft_version: 4, outcome: "complete", objects: objects}).value
    puts "active revision #{canvas.active.revision}"
    # lingara:end
  end
end
