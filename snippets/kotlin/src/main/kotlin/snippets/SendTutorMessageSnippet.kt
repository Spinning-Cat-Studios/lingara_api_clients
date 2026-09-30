package snippets

// lingara:begin sendTutorMessage
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.model.SendTutorMessageEvent
import com.getlingara.kotlin.model.TutorTurnRequest
// lingara:end

/** The documentation site's sendTutorMessage example. */
suspend fun sendTutorMessageSnippet(client: LingaraClient) {
    // lingara:begin sendTutorMessage
    val turn = TutorTurnRequest(message = "荔枝多少钱？", sourceLang = "en", targetLang = "zh", level = 2)
    client.sendTutorMessage(turn).use { stream ->
        stream.collect { event ->
            when (event) {
                is SendTutorMessageEvent.Delta -> print(event.data.text)
                is SendTutorMessageEvent.Notice -> println("\n(${event.data.message})")
            }
        }
    }
    // lingara:end
}
