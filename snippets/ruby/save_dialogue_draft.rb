# frozen_string_literal: true

module LingaraSnippets
  def self.save_dialogue_draft(client, dialogue_id)
    # lingara:begin saveDialogueDraft
    # Read, change, save at the draft_version you read. A stale version is
    # 409 dialogue_conflict: never retried, and never merged for you.
    draft = client.dialogues.get_dialogue(dialogue_id).value.draft
    # The definition is an open Hash, string keys as the server sent them, so
    # keys this library does not know (x-layout, say) survive the save.
    definition = draft.definition.merge("start" => "greet")
    begin
      saved = client.dialogues.save_dialogue_draft(dialogue_id, {definition: definition, draft_version: draft.draft_version}).value
      puts "saved; the next save sends draft_version #{saved.draft.draft_version}"
    rescue Lingara::ApiError => e
      raise unless e.code == "dialogue_conflict"
      # The draft moved since you read it, perhaps by your own retried save.
      # Re-read, compare, and decide. With no draft at all, send
      # draft_version 0 to open one.
      current = client.dialogues.get_dialogue(dialogue_id).value.draft
      puts "changed elsewhere: the draft is now at version #{current&.draft_version || 0}"
    end
    # lingara:end
  end
end
