package snippets

// lingara:begin dryRunCanvasPlan
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.canvas.canvases
import com.getlingara.kotlin.model.CanvasPlan
import com.getlingara.kotlin.model.CanvasVerdictKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
// lingara:end

/** The documentation site's dryRunCanvasPlan example. */
suspend fun dryRunCanvasPlanSnippet(client: LingaraClient) {
    // lingara:begin dryRunCanvasPlan
    val createClient =
        mapOf(
            "handle" to JsonPrimitive("c1"),
            "kind" to JsonPrimitive("client"),
            "op" to JsonPrimitive("create"),
            "body" to
                buildJsonObject {
                    put("name", "Lesson server")
                    put("scopes", JsonArray(listOf(JsonPrimitive("events:read"))))
                },
        )
    val dryRun = client.canvases.dryRunCanvasPlan(CanvasPlan(ops = listOf(createClient))).body
    // A refused op is an answer, not an error: branch on its code; its English error is a fallback.
    dryRun.verdicts
        .filter { it.verdict == CanvasVerdictKind.REFUSED }
        .forEach { println("${it.handle}: ${it.code}") }
    // lingara:end
}
