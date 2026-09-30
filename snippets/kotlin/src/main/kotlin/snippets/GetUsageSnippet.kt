package snippets

import com.getlingara.kotlin.LingaraClient

/** The documentation site's getUsage example. */
suspend fun getUsageSnippet(client: LingaraClient) {
    // lingara:begin getUsage
    val usage = client.getUsage().body
    for (row in usage.allowance) {
        println("${row.feature} (${row.window}): ${row.remaining} of ${row.limit} left")
    }
    // lingara:end
}
