package snippets

// lingara:begin getDialogue
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.dialogue.dialogues
// lingara:end

/** The documentation site's getDialogue example. */
suspend fun getDialogueSnippet(
    client: LingaraClient,
    dialogueId: String,
) {
    // lingara:begin getDialogue
    val dialogue = client.dialogues.getDialogue(dialogueId).body
    // The draft and the active revision, inline; either may be null.
    dialogue.draft?.let { println("draft at version ${it.draftVersion}") }
    dialogue.active?.let { println("active revision ${it.revision} since ${it.activatedAt}") }
    // lingara:end
}
