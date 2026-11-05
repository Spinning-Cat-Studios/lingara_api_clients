# frozen_string_literal: true

module LingaraSnippets
  def self.plan_dialogue(client, document)
    # lingara:begin planDialogue
    # The first plan of a Procedural dialogue. Inside the embed kits the kit
    # calls this for you. Never retried: each call spends one embed:play
    # unit, a keep too. The document is your dialogue.v2 one, sent as given.
    answer = client.plan_dialogue(document: document, npc: "ma_ren", reason: "start", id_prefix: "p1", turns: 0, beats: 0).value
    if answer.status == "planned"
      answer.fragment.beats.each { |beat| puts "#{beat.id} #{beat.atom}" }
    else
      # A keep is a result: your frontier stands (on a first plan, walk the seed).
      puts "kept: #{answer.reason}"
    end
    # lingara:end
    answer
  end
end
