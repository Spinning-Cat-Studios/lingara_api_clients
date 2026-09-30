package snippets;

import com.getlingara.client.EventStream;
import com.getlingara.client.LingaraClient;
// lingara:begin generateVocabulary
import com.getlingara.client.model.GenerateVocabularyEvent;
import com.getlingara.client.model.VocabRequest;

// lingara:end

/** The documentation site's generateVocabulary example. */
public final class GenerateVocabulary {
  private GenerateVocabulary() {}

  static void run(LingaraClient client) {
    // lingara:begin generateVocabulary
    VocabRequest request = new VocabRequest().level(2).sourceLang("en").targetLang("zh").count(8);
    try (EventStream<GenerateVocabularyEvent> stream = client.generateVocabulary(request)) {
      for (GenerateVocabularyEvent event : stream) {
        if (event instanceof GenerateVocabularyEvent.Item item) {
          System.out.println(item.data().getWord() + " " + item.data().getTranslation());
        }
      }
    }
    // lingara:end
  }
}
