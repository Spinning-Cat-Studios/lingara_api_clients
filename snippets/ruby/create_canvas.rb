# frozen_string_literal: true

module LingaraSnippets
  def self.create_canvas(client)
    # lingara:begin createCanvas
    # A canvas from a recipe: a client that tells your server, through a
    # webhook, when a lesson plan is ready. The answer does not echo recipe.
    definition = {
      schema: 2,
      nodes: [{id: "n1", kind: "client", position: [0, 0]}, {id: "n2", kind: "webhook", position: [1, 0]}],
      edges: [{from: "n1", to: "n2"}]
    }
    canvas = client.canvases.create_canvas({name: "Tell my server when a plan is ready", recipe: "lesson_plan_ready",
                                            definition: definition}).value
    puts "created canvas #{canvas.id} at draft_version #{canvas.draft.draft_version}"
    canvas.id
    # lingara:end
  end
end
