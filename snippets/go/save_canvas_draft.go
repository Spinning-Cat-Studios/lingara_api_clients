package snippets

import (
	"context"
	"errors"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func saveCanvasDraft(ctx context.Context, client *lingara.Client, definition lingara.CanvasDefinition, draftVersion int32) error {
	// lingara:begin saveCanvasDraft
	const canvasID = "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b"
	canvases := client.Canvases()
	saved, err := canvases.SaveCanvasDraft(ctx, canvasID, lingara.SaveCanvasDraftRequest{Definition: definition, DraftVersion: draftVersion})
	var apiErr *lingara.APIError
	if errors.As(err, &apiErr) && apiErr.Code == "canvas_conflict" {
		// The draft moved since you read it, perhaps by your own retried
		// save. It is never retried for you: re-read, compare the draft with
		// the one you sent, and decide which to keep.
		current, err := canvases.GetCanvas(ctx, canvasID)
		if err != nil {
			return err
		}
		fmt.Println("the draft is now at", current.Value.Draft.DraftVersion)
		// To keep yours, save again with current.Value.Draft.DraftVersion.
		return nil
	}
	if err != nil {
		return err
	}
	fmt.Println("saved; the next save sends", saved.Value.Draft.DraftVersion)
	// lingara:end
	return nil
}
