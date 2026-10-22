package snippets

// lingara:begin linkEmbedPlayer
import com.getlingara.kotlin.AccessToken
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.LingaraClientBuilder
import com.getlingara.kotlin.TokenSource
import com.getlingara.kotlin.embed.linkEmbedPlayer
// lingara:end

// lingara:begin linkEmbedPlayer
// The link is called with the player's own delegated lgr_at_ token: the one your server received
// when the player's Lingara account granted your app access (the authorization-code flow, which
// this library does not run yet). A client built from your client credentials is refused
// 403 embed_link_needs_grant. This source hands back the same token every time, so a revoked grant
// surfaces as a 401 after one repeat.
fun delegatedClient(
    accessToken: String,
    configure: LingaraClientBuilder.() -> Unit = {},
): LingaraClient {
    val token = AccessToken(accessToken)
    return LingaraClient {
        configure()
        tokenSource =
            object : TokenSource {
                override suspend fun token(): AccessToken = token

                override fun invalidate(token: AccessToken) {}
            }
    }
}

/** The documentation site's linkEmbedPlayer example. */
suspend fun linkEmbedPlayerSnippet(
    client: LingaraClient,
    playerId: String,
) {
    val link = client.linkEmbedPlayer("guild/$playerId").body
    // EXISTING is a success too. Call again while kept has lesson_plans: a plan still being written
    // moves on a later call.
    println("$playerId is ${link.status} as ${link.subject}")
}
// lingara:end
