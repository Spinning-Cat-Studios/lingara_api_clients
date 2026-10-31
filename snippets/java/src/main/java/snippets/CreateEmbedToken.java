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
    // Pass on the Lingara-Embed-Version header the player kit sent, to hear when it is out of date.
    String embedVersion = "1.1.0";
    MintedToken minted =
        client
            .createEmbedToken(
                new EmbedTokenRequest().playerRef("guild/" + playerId).embedVersion(embedVersion))
            .body();
    // The subject is how events name this player: store it beside your own record.
    System.out.println("player " + playerId + " is " + minted.subject());
    // A notice is for you, the developer: the build is behind or deprecated.
    minted
        .updateAvailable()
        .ifPresent(n -> System.out.println("embed " + embedVersion + " is out of date: " + n));
    // Hand the token on to the player kit (lgr_et_…). It lives 900 s and is never refreshed:
    // the kit asks your server again, and you mint again.
    return Map.of("token", minted.token().exposeSecret(), "expires_at", minted.expiresAt());
    // lingara:end
  }
}
