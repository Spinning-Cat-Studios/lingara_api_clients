package snippets

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func createEmbedToken(ctx context.Context, client *lingara.Client, w http.ResponseWriter) error {
	// lingara:begin createEmbedToken
	// On your server, from a metered client holding embed:mint. The token
	// (lgr_et_…) lives 900 s and is never refreshed: mint again when the
	// player's device asks. Pass on the Lingara-Embed-Version header the
	// player kit sent, to be told when that build is out of date.
	scopes := []lingara.EmbedTokenRequestScopes{"embed:play", "events:read"}
	embedVersion := "1.1.0"
	minted, err := client.CreateEmbedToken(ctx, lingara.EmbedTokenRequest{PlayerRef: "player-1001", Scopes: &scopes, EmbedVersion: &embedVersion})
	if err != nil {
		return err
	}
	// Store the subject beside your player: it is how an event names them.
	fmt.Println("player-1001 is", minted.Value.Subject)
	// A notice is for you, the developer: the build is behind or deprecated.
	if notice := minted.Value.UpdateAvailable; notice != nil {
		fmt.Println("embed", embedVersion, "is out of date; deprecated:", notice.Deprecated)
	}
	// Hand the token and its expiry to the player's device, and log neither.
	return json.NewEncoder(w).Encode(map[string]any{
		"token":      minted.Value.Token.ExposeSecret(),
		"expires_at": minted.Value.ExpiresAt,
		"expires_in": minted.Value.ExpiresIn.Seconds(),
	})
	// lingara:end
}
