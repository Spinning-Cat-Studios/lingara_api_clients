package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func activateCanvas(ctx context.Context, client *lingara.Client) error {
	// lingara:begin activateCanvas
	// After applying the plan yourself: the draft you applied, and the
	// object each node made. A replay at a promoted draft_version answers
	// 409 canvas_conflict and writes nothing.
	canvas, err := client.Canvases().ActivateCanvas(ctx, "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b", lingara.ActivateCanvasRequest{
		DraftVersion: 2,
		Outcome:      lingara.CanvasActivationOutcomeComplete,
		Objects:      map[string]lingara.CanvasObjectRef{"n1": {ID: "0b9e4c2d-7a1f-4e3b-9d8c-5f6a7b8c9d0e"}},
	})
	if err != nil {
		return err
	}
	fmt.Println("active revision", canvas.Value.Active.Revision)
	// lingara:end
	return nil
}
