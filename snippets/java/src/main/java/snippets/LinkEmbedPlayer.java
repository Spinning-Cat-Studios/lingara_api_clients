package snippets;

// lingara:begin linkEmbedPlayer
import com.getlingara.client.AccessToken;
import com.getlingara.client.LingaraClient;
import com.getlingara.client.TokenSource;
import com.getlingara.client.model.EmbedPlayerLink;

// lingara:end

/** The documentation site's linkEmbedPlayer example. */
public final class LinkEmbedPlayer {
  private LinkEmbedPlayer() {}

  // lingara:begin linkEmbedPlayer
  // The link is called with the player's own delegated lgr_at_ token: the one your server received
  // when the player's Lingara account granted your app access (the authorization-code flow, which
  // this library does not run yet). A client built from your client credentials is refused
  // 403 embed_link_needs_grant. This source hands back the same token every time, so a revoked
  // grant surfaces as a 401 after one repeat.
  static LingaraClient delegatedClient(String accessToken, LingaraClient.Builder builder) {
    AccessToken token = new AccessToken(accessToken);
    TokenSource source =
        new TokenSource() {
          @Override
          public AccessToken token() {
            return token;
          }

          @Override
          public void invalidate(AccessToken refused) {}
        };
    return builder.tokenSource(source).build();
  }

  static void linkEmbedPlayer(LingaraClient client, String playerId) {
    EmbedPlayerLink link = client.linkEmbedPlayer("guild/" + playerId).body();
    // EXISTING is a success too. Call again while kept has lesson_plans: a plan still being written
    // moves on a later call.
    System.out.println(playerId + " is " + link.getStatus() + " as " + link.getSubject());
  }
  // lingara:end
}
