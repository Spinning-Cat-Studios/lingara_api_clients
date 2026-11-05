package snippets

// lingara:begin planDialogue
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.embed.planDialogue
import com.getlingara.kotlin.model.DialoguePlanRequest
import com.getlingara.kotlin.model.PlanReason
import kotlinx.serialization.json.JsonObject
// lingara:end

/** The documentation site's planDialogue example. */
suspend fun planDialogueSnippet(
    client: LingaraClient,
    document: JsonObject,
) {
    // lingara:begin planDialogue
    // The first plan of a Procedural dialogue. Inside the embed kits the kit calls this for you.
    val request =
        DialoguePlanRequest(
            document = document, // your dialogue.v2 Procedural document, sent as given
            npc = "ma_ren",
            reason = PlanReason.START,
            idPrefix = "p1",
            turns = 0,
            beats = 0,
        )
    // Sent once, never retried: each call spends one embed:play unit, a keep too.
    val answer = client.planDialogue(request).body
    val fragment = answer.fragment
    if (fragment != null) {
        fragment.beats.forEach { println("${it.id} ${it.atom}") }
    } else {
        // A keep is a result: your frontier stands (on a first plan, walk the seed).
        println("kept: ${answer.reason}")
    }
    // lingara:end
}
