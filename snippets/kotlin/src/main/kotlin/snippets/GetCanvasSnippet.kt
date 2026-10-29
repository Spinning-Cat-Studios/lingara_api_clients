package snippets

// lingara:begin getCanvas
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.canvas.canvases
// lingara:end

/** The documentation site's getCanvas example. */
suspend fun getCanvasSnippet(
    client: LingaraClient,
    canvasId: String,
) {
    // lingara:begin getCanvas
    val canvas = client.canvases.getCanvas(canvasId).body
    // Either revision may be null: no draft since the last activate, or never activated.
    canvas.draft?.let { println("draft at version ${it.draftVersion}") }
    canvas.active?.let { println("live revision ${it.revision}") }
    // lingara:end
}
