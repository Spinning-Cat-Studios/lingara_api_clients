# frozen_string_literal: true

module LingaraSnippets
  def self.create_dialogue(client)
    # lingara:begin createDialogue
    # The definition is a dialogue.v1 document; the server validates it on
    # every save. Warnings are advice, never a refusal.
    definition = {
      schema: "dialogue.v1",
      id: "night_market_keepers",
      start: "greet",
      npcs: [{id: "old_wei", name: "Old Wei"}],
      checkpoints: [{id: "greet", goal: "Greet the stranger", may_disclose: [],
                     edges: [{id: "thanked", to: "success", when: "the player says thank you"}]}],
      facts: []
    }
    dialogue = client.dialogues.create_dialogue({name: "Night market", definition: definition}).value
    puts "created dialogue #{dialogue.id} at draft_version #{dialogue.draft.draft_version}"
    dialogue.warnings.each { |warning| puts "warning #{warning.code} at #{warning.path}" }
    dialogue.id
    # lingara:end
  end
end
