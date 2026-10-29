package snippets

// lingara:begin exportDialogue
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.dialogue.ExportDialogueOptions
import com.getlingara.kotlin.dialogue.ExportedDialogue
import com.getlingara.kotlin.dialogue.dialogues
// lingara:end

/** The documentation site's exportDialogue example. */
suspend fun exportDialogueSnippet(
    client: LingaraClient,
    dialogueId: String,
    cached: ExportedDialogue.Body?,
): ExportedDialogue.Body? {
    // lingara:begin exportDialogue
    // Cache by ETag: the body is canonical bytes, so store it exactly as received. A 304 still
    // spends one unit of the rate budget.
    val options = ExportDialogueOptions(ifNoneMatch = cached?.etag)
    return when (val exported = client.dialogues.exportDialogue(dialogueId, options).body) {
        is ExportedDialogue.Body -> {
            println("revision ${exported.revision} is new")
            exported
        }
        is ExportedDialogue.NotModified -> {
            println("revision ${exported.revision} is unchanged")
            cached
        }
    }
    // lingara:end
}
