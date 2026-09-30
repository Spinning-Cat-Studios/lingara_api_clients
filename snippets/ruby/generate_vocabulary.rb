# frozen_string_literal: true

module LingaraSnippets
  def self.generate_vocabulary(client)
    # lingara:begin generateVocabulary
    client.generate_vocabulary(level: 2, source_lang: "en", target_lang: "zh", count: 8) do |event|
      case event
      in Lingara::GenerateVocabularyEventItem => item then puts "#{item.data.word}: #{item.data.translation}"
      else nil
      end
    end
    # lingara:end
  end
end
