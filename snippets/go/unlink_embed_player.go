package snippets

import (
	"context"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func unlinkEmbedPlayer(ctx context.Context, client *lingara.Client) error {
	// lingara:begin unlinkEmbedPlayer
	// On your ordinary client holding embed:mint. A 404 embed_link_not_found
	// after a retried call means the link had already ended.
	if _, err := client.UnlinkEmbedPlayer(ctx, "player-1001"); err != nil {
		return err
	}
	// lingara:end
	return nil
}
