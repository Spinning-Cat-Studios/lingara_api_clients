package snippets

// lingara:begin getOpenApiDocument
import com.getlingara.kotlin.LingaraClient
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
// lingara:end

/** The documentation site's getOpenApiDocument example. */
suspend fun getOpenApiDocumentSnippet(client: LingaraClient) {
    // lingara:begin getOpenApiDocument
    val document = client.getOpenApiDocument().body
    println(
        document
            .getValue("info")
            .jsonObject
            .getValue("version")
            .jsonPrimitive.content,
    )
    // lingara:end
}

/** This operation needs no token, so a client with no credentials is enough. */
suspend fun getOpenApiDocumentExample() = getOpenApiDocumentSnippet(LingaraClient {})
