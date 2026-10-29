package snippets

// lingara:begin createCanvas
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.canvas.canvases
import com.getlingara.kotlin.model.CanvasDefinition
import com.getlingara.kotlin.model.CanvasEdge
import com.getlingara.kotlin.model.CanvasNode
import com.getlingara.kotlin.model.CanvasNodeKind
import com.getlingara.kotlin.model.CanvasRecipe
import com.getlingara.kotlin.model.CreateCanvasRequest
// lingara:end

/** The documentation site's createCanvas example. */
suspend fun createCanvasSnippet(client: LingaraClient): String {
    // lingara:begin createCanvas
    // A client node wired to a webhook node: tell my server when a lesson plan is ready.
    val definition =
        CanvasDefinition(
            schema = 2,
            nodes =
                listOf(
                    CanvasNode(id = "n1", kind = CanvasNodeKind.CLIENT, position = listOf(0.0, 0.0)),
                    CanvasNode(id = "n2", kind = CanvasNodeKind.WEBHOOK, position = listOf(1.0, 0.0)),
                ),
            edges = listOf(CanvasEdge(from = "n1", to = "n2")),
        )
    val request =
        CreateCanvasRequest(
            definition = definition,
            name = "Tell my server when a lesson plan is ready",
            recipe = CanvasRecipe.LESSON_PLAN_READY,
        )
    val canvas = client.canvases.createCanvas(request).body
    // Keep the draft's version: the next saveCanvasDraft sends it back.
    println("${canvas.id} at draft ${canvas.draft?.draftVersion}")
    return canvas.id
    // lingara:end
}
