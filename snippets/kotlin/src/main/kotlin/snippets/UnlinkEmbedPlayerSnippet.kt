package snippets

// lingara:begin unlinkEmbedPlayer
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.embed.unlinkEmbedPlayer
// lingara:end

/** The documentation site's unlinkEmbedPlayer example. */
suspend fun unlinkEmbedPlayerSnippet(
    client: LingaraClient,
    playerId: String,
) {
    // lingara:begin unlinkEmbedPlayer
    // On your ordinary client holding embed:mint. A 404 embed_link_not_found after a retried call
    // means the link had already ended.
    client.unlinkEmbedPlayer("guild/$playerId")
    // lingara:end
}
