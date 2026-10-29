# frozen_string_literal: true

module LingaraSnippets
  def self.page_dialogues(client)
    # lingara:begin pageDialogues
    # Every dialogue the filters match, page by page, the filters resent each
    # time. It never sleeps or polls, and stops on the first error.
    client.dialogues.page_dialogues(name: "market").each do |dialogue|
      puts "#{dialogue.name}: #{dialogue.active_revision || "never activated"}"
    end
    # lingara:end
  end
end
