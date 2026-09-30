package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func getLessonPlan(ctx context.Context, client *lingara.Client) error {
	// lingara:begin getLessonPlan
	plan, err := client.GetLessonPlan(ctx, "3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37")
	if err != nil {
		return err
	}
	fmt.Println(plan.Value.Status, plan.Value.CreatedAt)
	// lingara:end
	return nil
}
