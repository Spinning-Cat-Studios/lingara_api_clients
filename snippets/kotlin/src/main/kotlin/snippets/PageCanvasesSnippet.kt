package snippets

// lingara:begin pageCanvases
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.canvas.CanvasFilters
import com.getlingara.kotlin.canvas.canvases
// lingara:end

/** The documentation site's pageCanvases example. */
suspend fun pageCanvasesSnippet(client: LingaraClient) {
    // lingara:begin pageCanvases
    // Every canvas made from the lesson-plan recipe, one page fetched at a time.
    client.canvases
        .pageCanvases(CanvasFilters(recipes = listOf("lesson_plan_ready")))
        .collect { println("${it.id} ${it.name}") }
    // lingara:end
}
