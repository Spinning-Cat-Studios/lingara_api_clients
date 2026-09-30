package snippets

// lingara:begin generateVocabulary
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.model.GenerateVocabularyEvent
import com.getlingara.kotlin.model.VocabRequest
// lingara:end

/** The documentation site's generateVocabulary example. */
suspend fun generateVocabularySnippet(client: LingaraClient) {
    // lingara:begin generateVocabulary
    val request = VocabRequest(level = 2, sourceLang = "en", targetLang = "zh", count = 8)
    // The request is sent here; collect the stream once, inside use {}.
    client.generateVocabulary(request).use { stream ->
        stream.collect { event ->
            when (event) {
                is GenerateVocabularyEvent.Item -> println("${event.data.word} ${event.data.translation}")
                is GenerateVocabularyEvent.Started -> Unit
            }
        }
    }
    // lingara:end
}
