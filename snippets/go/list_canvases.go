package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func listCanvases(ctx context.Context, client *lingara.Client) error {
	// lingara:begin listCanvases
	// One page; PageCanvases walks them all.
	page, err := client.Canvases().ListCanvases(ctx, lingara.ListCanvasesOptions{
		Filters: lingara.CanvasFilters{Limit: 20, Status: lingara.ListCanvasesParamsStatusLive, Kinds: []string{"webhook"}},
	})
	if err != nil {
		return err
	}
	for _, canvas := range page.Value.Items {
		fmt.Println(canvas.ID, canvas.Name, canvas.Recipe, len(canvas.Objects))
	}
	if page.Value.HasMore {
		fmt.Println("next page from", *page.Value.NextCursor)
	}
	// lingara:end
	return nil
}
