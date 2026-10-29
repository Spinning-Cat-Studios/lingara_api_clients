package snippets

// lingara:begin renameCanvas
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.canvas.canvases
import com.getlingara.kotlin.model.RenameCanvasRequest
// lingara:end

/** The documentation site's renameCanvas example. */
suspend fun renameCanvasSnippet(
    client: LingaraClient,
    canvasId: String,
) {
    // lingara:begin renameCanvas
    client.canvases.renameCanvas(canvasId, RenameCanvasRequest(name = "Lesson plan webhook"))
    // lingara:end
}
