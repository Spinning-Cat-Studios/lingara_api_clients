package snippets

import (
	"context"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func deleteEmbedPlayer(ctx context.Context, client *lingara.Client) error {
	// lingara:begin deleteEmbedPlayer
	// Deletes the player and revokes their tokens. An unknown player is
	// still a success, so it is safe to repeat.
	if _, err := client.DeleteEmbedPlayer(ctx, "player-1001"); err != nil {
		return err
	}
	// lingara:end
	return nil
}
