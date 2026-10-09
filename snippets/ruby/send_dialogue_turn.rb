# frozen_string_literal: true

module LingaraSnippets
  def self.send_dialogue_turn(client)
    # lingara:begin sendDialogueTurn
    npc = {name: "Auntie Lin", persona: "a street-food vendor who likes to haggle"}
    history = [{speaker: "npc", text: "来来来，刚出锅的饺子！"}]
    line = "饺子多少钱？"
    reply = +""
    # Sent once, never retried: each turn spends NPC cells.
    client.send_dialogue_turn(npc: npc, source_lang: "en", target_lang: "zh", level: 3, line: line, history: history) do |event|
      case event
      in Lingara::SendDialogueTurnEventDelta => delta then reply << delta.data.text
      in Lingara::SendDialogueTurnEventNotice => notice then warn notice.data.message
      else nil
      end
    end
    puts reply
    # You keep the window: at most 12 entries, the reply cut to its first 500 characters.
    history = (history + [{speaker: "player", text: line}, {speaker: "npc", text: reply[0, 500]}]).last(12)
    # lingara:end
    history
  end
end
