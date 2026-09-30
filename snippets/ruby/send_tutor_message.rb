# frozen_string_literal: true

module LingaraSnippets
  def self.send_tutor_message(client)
    # lingara:begin sendTutorMessage
    history = [{role: "user", content: "你好"}, {role: "assistant", content: "你好！你想练习什么？"}]
    client.send_tutor_message(message: "我想点菜", history: history, source_lang: "en", target_lang: "zh") do |event|
      case event
      in Lingara::SendTutorMessageEventDelta => delta then print delta.data.text
      in Lingara::SendTutorMessageEventNotice => notice then warn notice.data.message
      else nil
      end
    end
    puts
    # lingara:end
  end
end
