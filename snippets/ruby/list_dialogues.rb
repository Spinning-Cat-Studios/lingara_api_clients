# frozen_string_literal: true

module LingaraSnippets
  def self.list_dialogues(client)
    # lingara:begin listDialogues
    # One page of your dialogues, newest first. Pass page.next_cursor back as
    # cursor: for the next page, or let page_dialogues walk them all.
    page = client.dialogues.list_dialogues(name: "market", limit: 20).value
    page.items.each do |dialogue|
      puts "#{dialogue.name} (#{dialogue.ir_id}): #{dialogue.npc_count} NPC(s), #{dialogue.checkpoint_count} checkpoint(s)"
    end
    # lingara:end
  end
end
