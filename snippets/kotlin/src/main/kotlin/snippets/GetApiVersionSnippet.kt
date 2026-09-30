package snippets

import com.getlingara.kotlin.LingaraClient

/** The documentation site's getApiVersion example. */
suspend fun getApiVersionSnippet(client: LingaraClient) {
    // lingara:begin getApiVersion
    val version = client.getApiVersion("2026-09-knowing-tenpounder").body
    println("${version.id} ${version.state}")
    // lingara:end
}

/** This operation needs no token, so a client with no credentials is enough. */
suspend fun getApiVersionExample() = getApiVersionSnippet(LingaraClient {})
