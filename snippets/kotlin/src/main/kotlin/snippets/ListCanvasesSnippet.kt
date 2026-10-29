package snippets

// lingara:begin listCanvases
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.canvas.CanvasFilters
import com.getlingara.kotlin.canvas.canvases
// lingara:end

/** The documentation site's listCanvases example. */
suspend fun listCanvasesSnippet(client: LingaraClient): String? {
    // lingara:begin listCanvases
    // One page of your live canvases that use a webhook; pageCanvases walks every page.
    val page = client.canvases.listCanvases(CanvasFilters(status = "live", kinds = listOf("webhook"), limit = 20)).body
    page.items.forEach { println("${it.name} (${it.recipe})") }
    // Pass it back as the cursor for the next page, while hasMore is true.
    return page.nextCursor
    // lingara:end
}
