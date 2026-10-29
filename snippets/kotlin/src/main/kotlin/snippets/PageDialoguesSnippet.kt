package snippets

// lingara:begin pageDialogues
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.dialogue.DialogueFilters
import com.getlingara.kotlin.dialogue.dialogues
// lingara:end

/** The documentation site's pageDialogues example. */
suspend fun pageDialoguesSnippet(client: LingaraClient) {
    // lingara:begin pageDialogues
    // Every dialogue the filters match: the helper follows nextCursor and resends the filters on
    // every page. It never sleeps or polls.
    client.dialogues
        .pageDialogues(DialogueFilters(name = "market"))
        .collect { println("${it.id} ${it.name} ${it.activeRevision ?: "never activated"}") }
    // lingara:end
}
