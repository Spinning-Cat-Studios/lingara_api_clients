package snippets

// lingara:begin saveDialogueDraft
import com.getlingara.kotlin.ApiException
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.dialogue.dialogues
import com.getlingara.kotlin.model.SaveDialogueDraftRequest
import kotlinx.serialization.json.JsonObject
// lingara:end

/** The documentation site's saveDialogueDraft example. */
suspend fun saveDialogueDraftSnippet(
    client: LingaraClient,
    dialogueId: String,
    edited: JsonObject,
    readAt: Int,
) {
    // lingara:begin saveDialogueDraft
    // Send back the draftVersion you read the draft at; 0 opens a draft when there is none.
    try {
        val request = SaveDialogueDraftRequest(definition = edited, draftVersion = readAt)
        val saved = client.dialogues.saveDialogueDraft(dialogueId, request).body
        println("saved; the next save sends ${saved.draft?.draftVersion}")
    } catch (e: ApiException) {
        if (e.code != "dialogue_conflict") throw e
        // The draft moved since you read it, perhaps by your own retried save. It is never retried
        // for you: re-read, compare, and decide. With no draft at all, send draftVersion 0.
        val current = client.dialogues.getDialogue(dialogueId).body
        println("changed elsewhere; the draft is now at ${current.draft?.draftVersion ?: 0}")
    }
    // lingara:end
}
