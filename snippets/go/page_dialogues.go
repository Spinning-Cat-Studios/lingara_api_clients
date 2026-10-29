package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func pageDialogues(ctx context.Context, client *lingara.Client) error {
	// lingara:begin pageDialogues
	// Every dialogue the filters match: the helper follows next_cursor and
	// resends the filters on every page. It never sleeps or polls.
	for d, err := range client.Dialogues().PageDialogues(ctx, lingara.DialogueFilters{Name: "market"}) {
		if err != nil {
			return err
		}
		if d.ActiveRevision == nil {
			fmt.Println(d.ID, d.Name, "never activated")
		} else {
			fmt.Println(d.ID, d.Name, "active revision", *d.ActiveRevision)
		}
	}
	// lingara:end
	return nil
}
