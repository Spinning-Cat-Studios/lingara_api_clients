package snippets

// lingara:begin listDialogues
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.dialogue.DialogueFilters
import com.getlingara.kotlin.dialogue.dialogues
// lingara:end

/** The documentation site's listDialogues example. */
suspend fun listDialoguesSnippet(client: LingaraClient): String? {
    // lingara:begin listDialogues
    // One page of dialogues whose name contains "market"; pageDialogues walks every page.
    val page = client.dialogues.listDialogues(DialogueFilters(name = "market", limit = 20)).body
    page.items.forEach { println("${it.id} ${it.name} ${it.irId}: ${it.npcCount} NPCs, ${it.checkpointCount} checkpoints") }
    // Pass it back as the cursor for the next page, while hasMore is true.
    return page.nextCursor
    // lingara:end
}
