package snippets

// lingara:begin activateDialogue
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.dialogue.dialogues
import com.getlingara.kotlin.model.ActivateDialogueRequest
// lingara:end

/** The documentation site's activateDialogue example. */
suspend fun activateDialogueSnippet(
    client: LingaraClient,
    dialogueId: String,
) {
    // lingara:begin activateDialogue
    // Promote the draft you read: a stale or missing draft is 409 dialogue_conflict.
    val draft = client.dialogues.getDialogue(dialogueId).body.draft
    if (draft != null) {
        val activated = client.dialogues.activateDialogue(dialogueId, ActivateDialogueRequest(draftVersion = draft.draftVersion)).body
        println("active revision ${activated.active?.revision}")
    }
    // lingara:end
}
