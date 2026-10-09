package snippets;

import com.getlingara.client.LingaraClient;
// lingara:begin createEmbedToken
import com.getlingara.client.MintedToken;
import com.getlingara.client.model.EmbedTokenRequest;
import java.util.Map;

// lingara:end

/** The documentation site's createEmbedToken example. */
public final class CreateEmbedToken {
  private CreateEmbedToken() {}

  static Map<String, Object> run(LingaraClient client, String playerId) {
    // lingara:begin createEmbedToken
    // On your server, from a metered client holding embed:mint; never on the player's device.
    MintedToken minted =
        client.createEmbedToken(new EmbedTokenRequest().playerRef("guild/" + playerId)).body();
    // The subject is how events name this player: store it beside your own record.
    System.out.println("player " + playerId + " is " + minted.subject());
    // Hand the token on to the player kit (lgr_et_…). It lives 900 s and is never refreshed:
    // the kit asks your server again, and you mint again.
    return Map.of("token", minted.token().exposeSecret(), "expires_at", minted.expiresAt());
    // lingara:end
  }
}
