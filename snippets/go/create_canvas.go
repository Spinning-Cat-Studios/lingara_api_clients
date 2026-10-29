package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func createCanvas(ctx context.Context, client *lingara.Client) error {
	// lingara:begin createCanvas
	name := "Tell my server when a lesson plan is ready"
	recipe := lingara.CanvasRecipeLessonPlanReady
	edges := []lingara.CanvasEdge{{From: "n1", To: "n2"}}
	canvas, err := client.Canvases().CreateCanvas(ctx, lingara.CreateCanvasRequest{
		Name:   &name,
		Recipe: &recipe,
		Definition: lingara.CanvasDefinition{Schema: 2, Edges: &edges, Nodes: []lingara.CanvasNode{
			{ID: "n1", Kind: lingara.CanvasNodeKindClient, Position: []float64{0, 0}},
			{ID: "n2", Kind: lingara.CanvasNodeKindWebhook, Position: []float64{1, 0}},
		}},
	})
	if err != nil {
		return err
	}
	fmt.Println(canvas.Value.ID, canvas.Value.Draft.DraftVersion)
	// lingara:end
	return nil
}
