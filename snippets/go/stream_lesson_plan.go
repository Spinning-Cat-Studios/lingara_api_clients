package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func streamLessonPlan(ctx context.Context, client *lingara.Client) error {
	// lingara:begin streamLessonPlan
	// Rejoin a plan still generating, with the plan_id from its started event.
	s, err := client.StreamLessonPlan(ctx, "3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37")
	if err != nil {
		return err
	}
	defer s.Close()
	for ev, err := range s.Events() {
		if err != nil {
			return err
		}
		switch e := ev.(type) {
		case lingara.StreamLessonPlanEventResult:
			fmt.Println("ready:", e.Data.Plan.ID)
		case lingara.StreamLessonPlanEventPending:
			fmt.Println("still", e.Data.Status, "- rejoin later")
		}
	}
	// lingara:end
	return nil
}
