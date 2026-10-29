package snippets

// lingara:begin activateCanvas
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.canvas.canvases
import com.getlingara.kotlin.model.ActivateCanvasRequest
import com.getlingara.kotlin.model.CanvasActivationOutcome
import com.getlingara.kotlin.model.CanvasObjectRef
// lingara:end

/** The documentation site's activateCanvas example. */
suspend fun activateCanvasSnippet(
    client: LingaraClient,
    canvasId: String,
    madeClientId: String,
) {
    // lingara:begin activateCanvas
    // After your apply made the objects, promote the draft with what each node became. A repeat at
    // the same draftVersion answers 409 canvas_conflict and writes nothing.
    val request =
        ActivateCanvasRequest(
            draftVersion = 2,
            outcome = CanvasActivationOutcome.COMPLETE,
            objects = mapOf("n1" to CanvasObjectRef(id = madeClientId)),
        )
    client.canvases.activateCanvas(canvasId, request)
    // lingara:end
}
