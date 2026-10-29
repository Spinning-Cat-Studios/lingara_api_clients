# frozen_string_literal: true

module LingaraSnippets
  def self.export_dialogue(client, dialogue_id, cached = nil)
    # lingara:begin exportDialogue
    # Cache by ETag: the body is canonical bytes, so store it exactly as
    # received. A 304 still spends one unit of the rate budget.
    exported = client.dialogues.export_dialogue(dialogue_id, if_none_match: cached&.fetch(:etag)).value
    case exported
    in Lingara::ExportedDialogue::Body
      cached = {etag: exported.etag, ir_json: exported.ir_json}
      puts "revision #{exported.revision} is new"
    in Lingara::ExportedDialogue::NotModified
      puts "revision #{exported.revision} is unchanged"
    end
    # lingara:end
    cached
  end
end
