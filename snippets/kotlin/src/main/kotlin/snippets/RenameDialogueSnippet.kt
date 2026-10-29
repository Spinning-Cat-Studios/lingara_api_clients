package snippets

// lingara:begin renameDialogue
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.dialogue.dialogues
import com.getlingara.kotlin.model.RenameDialogueRequest
// lingara:end

/** The documentation site's renameDialogue example. */
suspend fun renameDialogueSnippet(
    client: LingaraClient,
    dialogueId: String,
) {
    // lingara:begin renameDialogue
    val renamed = client.dialogues.renameDialogue(dialogueId, RenameDialogueRequest(name = "Night market keepers")).body
    println(renamed.name)
    // lingara:end
}
