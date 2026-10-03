package snippets

import (
	"context"
	"errors"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func listEvents(ctx context.Context, client *lingara.Client, savedCursor string) (string, error) {
	// lingara:begin listEvents
	// Catch up from the cursor you saved last time; with none, start from now.
	feed := client.Events(ctx, lingara.EventsOptions{Cursor: savedCursor})
	for {
		ev, err := feed.Next(ctx)
		if errors.Is(err, lingara.ErrNoMoreEvents) {
			break
		}
		if err != nil {
			return "", err // 410 cursor_expired: start again with no cursor
		}
		fmt.Println(ev.Meta().Type, ev.Meta().ID)
	}
	savedCursor = feed.Cursor() // save it, and ask again later
	// lingara:end
	return savedCursor, nil
}
