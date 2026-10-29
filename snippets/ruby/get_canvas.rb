# frozen_string_literal: true

module LingaraSnippets
  def self.get_canvas(client, canvas_id)
    # lingara:begin getCanvas
    # A canvas with its draft and its active revision inline; either may be
    # nil. Each carries the objects an apply made, by node handle.
    canvas = client.canvases.get_canvas(canvas_id).value
    puts "draft at version #{canvas.draft.draft_version}" if canvas.draft
    puts "active revision #{canvas.active.revision}" if canvas.active
    # lingara:end
  end
end
