package snippets;

import com.getlingara.client.LingaraClient;

/** The documentation site's deleteEmbedPlayer example. */
public final class DeleteEmbedPlayer {
  private DeleteEmbedPlayer() {}

  static void run(LingaraClient client, String playerId) {
    // lingara:begin deleteEmbedPlayer
    // Deletes the player's Lingara data and revokes its tokens. An unknown player is a success
    // too, so this is safe to repeat, and it works while embedding is switched off.
    client.deleteEmbedPlayer("guild/" + playerId);
    // lingara:end
  }
}
