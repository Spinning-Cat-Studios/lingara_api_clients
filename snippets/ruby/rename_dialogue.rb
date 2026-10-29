# frozen_string_literal: true

module LingaraSnippets
  def self.rename_dialogue(client, dialogue_id)
    # lingara:begin renameDialogue
    dialogue = client.dialogues.rename_dialogue(dialogue_id, {name: "Night market keepers"}).value
    puts "renamed to #{dialogue.name}"
    # lingara:end
  end
end
