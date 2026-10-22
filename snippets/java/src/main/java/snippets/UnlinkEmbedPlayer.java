package snippets;

import com.getlingara.client.LingaraClient;

/** The documentation site's unlinkEmbedPlayer example. */
public final class UnlinkEmbedPlayer {
  private UnlinkEmbedPlayer() {}

  static void run(LingaraClient client, String playerId) {
    // lingara:begin unlinkEmbedPlayer
    // On your ordinary client holding embed:mint. A 404 embed_link_not_found after a retried call
    // means the link had already ended.
    client.unlinkEmbedPlayer("guild/" + playerId);
    // lingara:end
  }
}
