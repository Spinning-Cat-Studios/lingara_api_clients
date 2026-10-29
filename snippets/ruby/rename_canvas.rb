# frozen_string_literal: true

module LingaraSnippets
  def self.rename_canvas(client, canvas_id)
    # lingara:begin renameCanvas
    canvas = client.canvases.rename_canvas(canvas_id, {name: "Lesson plan webhook"}).value
    puts "renamed to #{canvas.name}"
    # lingara:end
  end
end
