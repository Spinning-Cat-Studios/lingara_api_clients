package snippets

import com.getlingara.kotlin.LingaraClient

/** The documentation site's listApiVersions example. */
suspend fun listApiVersionsSnippet(client: LingaraClient) {
    // lingara:begin listApiVersions
    for (version in client.listApiVersions().body.versions) {
        println("${version.id} ${version.state} ${version.lts}")
    }
    // lingara:end
}

/** This operation needs no token, so a client with no credentials is enough. */
suspend fun listApiVersionsExample() = listApiVersionsSnippet(LingaraClient {})
