package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func createLessonPlan(ctx context.Context, client *lingara.Client) error {
	// lingara:begin createLessonPlan
	s, err := client.CreateLessonPlan(ctx, lingara.LessonPlanCreateRequest{
		Context:    "ordering at a night market",
		SourceLang: "en",
		TargetLang: "zh",
		Level:      2,
	})
	if err != nil {
		return err
	}
	defer s.Close()
	for ev, err := range s.Events() {
		if err != nil {
			return err
		}
		switch e := ev.(type) {
		case lingara.CreateLessonPlanEventStarted:
			fmt.Println("plan", e.Data.PlanID)
		case lingara.CreateLessonPlanEventPhase:
			fmt.Println("working:", e.Data.Phase)
		case lingara.CreateLessonPlanEventResult:
			if e.Data.Plan.Title != nil {
				fmt.Println(*e.Data.Plan.Title)
			}
		}
	}
	// lingara:end
	return nil
}
