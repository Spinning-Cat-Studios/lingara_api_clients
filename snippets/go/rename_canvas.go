package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func renameCanvas(ctx context.Context, client *lingara.Client) error {
	// lingara:begin renameCanvas
	canvas, err := client.Canvases().RenameCanvas(ctx, "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b",
		lingara.RenameCanvasRequest{Name: "Lesson plan webhook"})
	if err != nil {
		return err
	}
	fmt.Println(canvas.Value.Name)
	// lingara:end
	return nil
}
