package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func pageCanvases(ctx context.Context, client *lingara.Client) error {
	// lingara:begin pageCanvases
	// Every canvas that uses a webhook, page by page; the filters are resent
	// on every page and the loop ends after the last one.
	filters := lingara.CanvasFilters{Kinds: []string{"webhook"}}
	for canvas, err := range client.Canvases().PageCanvases(ctx, filters) {
		if err != nil {
			return err
		}
		fmt.Println(canvas.ID, canvas.Name, canvas.HasDraft)
	}
	// lingara:end
	return nil
}
