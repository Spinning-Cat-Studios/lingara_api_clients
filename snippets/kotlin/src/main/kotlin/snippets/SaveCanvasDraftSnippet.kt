package snippets

// lingara:begin saveCanvasDraft
import com.getlingara.kotlin.ApiException
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.canvas.canvases
import com.getlingara.kotlin.model.CanvasDefinition
import com.getlingara.kotlin.model.SaveCanvasDraftRequest
// lingara:end

/** The documentation site's saveCanvasDraft example. */
suspend fun saveCanvasDraftSnippet(
    client: LingaraClient,
    canvasId: String,
    edited: CanvasDefinition,
    readAt: Int,
) {
    // lingara:begin saveCanvasDraft
    // Send back the draft_version you read the draft at.
    try {
        client.canvases.saveCanvasDraft(canvasId, SaveCanvasDraftRequest(definition = edited, draftVersion = readAt))
    } catch (e: ApiException) {
        if (e.code != "canvas_conflict") throw e
        // Someone saved since you read it, or your own save landed before a retry. The library never
        // merges or re-sends: re-read, compare, and decide.
        val latest = client.canvases.getCanvas(canvasId).body
        if (latest.draft?.definition != edited) println("The draft changed; ask which one to keep.")
    }
    // lingara:end
}
