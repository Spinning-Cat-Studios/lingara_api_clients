package snippets

// lingara:begin linkEmbedPlayer
import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

// lingara:end

// lingara:begin linkEmbedPlayer
// The link is called with the player's own delegated lgr_at_ token: the one
// your server received when the player's Lingara account granted your app
// access (the authorization-code flow, which this library does not run yet).
// A client built from your client credentials is refused
// 403 embed_link_needs_grant. This source hands back the same token every
// time, so a revoked grant surfaces as a 401 after one repeat.
type delegatedToken struct{ token lingara.Token }

func (d delegatedToken) Token(context.Context) (lingara.Token, error) { return d.token, nil }
func (d delegatedToken) Invalidate(lingara.Token)                     {}

func delegatedClient(accessToken string, opts ...lingara.Option) (*lingara.Client, error) {
	source := delegatedToken{token: lingara.NewToken(accessToken)}
	return lingara.New(append(opts, lingara.WithTokenSource(source))...)
}

func linkEmbedPlayer(ctx context.Context, client *lingara.Client) error {
	res, err := client.LinkEmbedPlayer(ctx, "player-1001")
	if err != nil {
		return err
	}
	// LinkStatusExisting is a success too. Call again while
	// Kept["lesson_plans"] is non-zero: a plan still being written moves on
	// a later call.
	fmt.Printf("player-1001 is %s as %s\n", res.Value.Status, res.Value.Subject)
	return nil
}

// lingara:end
