package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func getCanvas(ctx context.Context, client *lingara.Client) error {
	// lingara:begin getCanvas
	canvas, err := client.Canvases().GetCanvas(ctx, "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b")
	if err != nil {
		return err
	}
	// Either revision may be nil: a canvas never activated has no Active.
	if draft := canvas.Value.Draft; draft != nil {
		fmt.Println("draft", draft.DraftVersion, len(draft.Definition.Nodes), "nodes")
	}
	if active := canvas.Value.Active; active != nil {
		fmt.Println("active revision", active.Revision, "since", active.ActivatedAt)
	}
	// lingara:end
	return nil
}
