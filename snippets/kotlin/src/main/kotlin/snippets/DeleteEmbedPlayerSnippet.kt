package snippets

// lingara:begin deleteEmbedPlayer
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.embed.deleteEmbedPlayer
// lingara:end

/** The documentation site's deleteEmbedPlayer example. */
suspend fun deleteEmbedPlayerSnippet(
    client: LingaraClient,
    playerId: String,
) {
    // lingara:begin deleteEmbedPlayer
    // Deletes the player's Lingara data and revokes its tokens. An unknown player is a success
    // too, so this is safe to repeat, and it works while embedding is switched off.
    client.deleteEmbedPlayer("guild/$playerId")
    // lingara:end
}
