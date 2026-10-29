package snippets

// lingara:begin createDialogue
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.dialogue.dialogues
import com.getlingara.kotlin.model.CreateDialogueRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
// lingara:end

/** The documentation site's createDialogue example. */
suspend fun createDialogueSnippet(client: LingaraClient): String {
    // lingara:begin createDialogue
    // The definition is a dialogue.v1 document, a JSON tree; the server validates it on every save.
    val definition =
        Json
            .parseToJsonElement(
                """
                {"schema": "dialogue.v1", "id": "night_market_keepers", "start": "greet",
                 "npcs": [{"id": "old_wei", "name": "Old Wei"}],
                 "checkpoints": [{"id": "greet", "goal": "Greet the stranger", "may_disclose": [],
                   "edges": [{"id": "thanked", "to": "success", "when": "the player says thank you"}]}],
                 "facts": []}
                """.trimIndent(),
            ).jsonObject
    val created = client.dialogues.createDialogue(CreateDialogueRequest(definition = definition, name = "Night market")).body
    println("${created.id} at draft ${created.draft?.draftVersion}")
    // Warnings are advice, never a refusal.
    created.warnings.forEach { println("warning ${it.code} at ${it.path}") }
    return created.id
    // lingara:end
}
