package snippets

// lingara:begin createEmbedToken
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.embed.createEmbedToken
import com.getlingara.kotlin.model.EmbedTokenRequest
// lingara:end

/** The documentation site's createEmbedToken example. */
suspend fun createEmbedTokenSnippet(
    client: LingaraClient,
    playerId: String,
): Map<String, String> {
    // lingara:begin createEmbedToken
    // On your server, from a metered client holding embed:mint; never on the player's device.
    val minted = client.createEmbedToken(EmbedTokenRequest(playerRef = "guild/$playerId")).body
    // The subject is how events name this player: store it beside your own record.
    println("player $playerId is ${minted.subject}")
    // Hand the token on to the player kit (lgr_et_…). It lives 900 s and is never refreshed:
    // the kit asks your server again, and you mint again.
    return mapOf("token" to minted.token.exposeSecret(), "expires_at" to minted.expiresAt)
    // lingara:end
}
