# frozen_string_literal: true

module LingaraSnippets
  def self.get_dialogue(client, dialogue_id)
    # lingara:begin getDialogue
    # A dialogue with its draft and its active revision inline; either may be
    # nil.
    dialogue = client.dialogues.get_dialogue(dialogue_id).value
    puts "draft at version #{dialogue.draft.draft_version}" if dialogue.draft
    puts "active revision #{dialogue.active.revision}" if dialogue.active
    # lingara:end
  end
end
