# frozen_string_literal: true

module LingaraSnippets
  def self.activate_dialogue(client, dialogue_id)
    # lingara:begin activateDialogue
    # Promote the draft at the draft_version you read. A stale or missing
    # draft, or a replay already promoted, is 409 dialogue_conflict and
    # writes nothing.
    dialogue = client.dialogues.activate_dialogue(dialogue_id, {draft_version: 4}).value
    puts "active revision #{dialogue.active.revision}"
    # lingara:end
  end
end
