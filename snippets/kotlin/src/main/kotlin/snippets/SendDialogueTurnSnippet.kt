package snippets

// lingara:begin sendDialogueTurn
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.embed.sendDialogueTurn
import com.getlingara.kotlin.model.DialogueEntry
import com.getlingara.kotlin.model.DialogueTurnRequest
import com.getlingara.kotlin.model.Npc
import com.getlingara.kotlin.model.SendDialogueTurnEvent
import com.getlingara.kotlin.model.Speaker
// lingara:end

/** The documentation site's sendDialogueTurn example. */
suspend fun sendDialogueTurnSnippet(
    client: LingaraClient,
    history: MutableList<DialogueEntry>,
) {
    // lingara:begin sendDialogueTurn
    val line = "饺子多少钱？"
    val turn =
        DialogueTurnRequest(
            npc = Npc(name = "Auntie Lin", persona = "a street-food vendor who likes to haggle"),
            sourceLang = "en",
            targetLang = "zh",
            level = 3,
            line = line,
            history = history.toList(), // at most 12 entries, each at most 500 characters
        )
    val reply = StringBuilder()
    // Sent once, never retried: each turn is billed, so a 429 is yours to answer.
    client.sendDialogueTurn(turn).use { stream ->
        stream.collect { event ->
            if (event is SendDialogueTurnEvent.Delta) reply.append(event.data.text)
        }
    }
    // The window is yours to keep: the reply goes back cut to its first 500 characters.
    val cut = reply.offsetByCodePoints(0, minOf(500, reply.codePointCount(0, reply.length)))
    history += DialogueEntry(speaker = Speaker.PLAYER, text = line)
    history += DialogueEntry(speaker = Speaker.NPC, text = reply.substring(0, cut))
    // lingara:end
}
