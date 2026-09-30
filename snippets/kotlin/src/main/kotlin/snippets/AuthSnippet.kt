package snippets

// lingara:begin auth
import com.getlingara.kotlin.LingaraClient

// lingara:end

/** The documentation site's authentication example. */
fun authSnippet(): LingaraClient {
    // lingara:begin auth
    val client =
        LingaraClient {
            clientCredentials(System.getenv("LINGARA_CLIENT_ID"), System.getenv("LINGARA_CLIENT_SECRET"))
        }
    // lingara:end
    return client
}
