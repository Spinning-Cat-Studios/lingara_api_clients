package snippets

import com.getlingara.kotlin.ApiException
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.LingaraException
import com.getlingara.kotlin.MaintenanceException
import com.getlingara.kotlin.OAuthException
import com.getlingara.kotlin.TransportException

/** The documentation site's error-handling example. */
suspend fun errorsSnippet(client: LingaraClient) {
    // lingara:begin errors
    try {
        client.getUsage()
    } catch (e: LingaraException) {
        when (e) {
            // A refusal from the API: status, code (stable) and message (localised).
            is ApiException -> println("${e.status} ${e.code} ${e.message}; retry after ${e.retryAfter}")
            // The token endpoint refused the credentials or the scopes.
            is OAuthException -> println("${e.status} ${e.error} ${e.description}")
            is MaintenanceException -> println("under maintenance; retry after ${e.retryAfter}")
            // No usable answer: CONNECT, TLS, RESET, TIMEOUT, and so on.
            is TransportException -> println("transport: ${e.kind}")
        }
    }
    // A cancelled call throws the caller's own CancellationException instead.
    // lingara:end
}
