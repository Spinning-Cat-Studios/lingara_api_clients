package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func streamEvents(ctx context.Context, client *lingara.Client, savedCursor string) error {
	// lingara:begin streamEvents
	// Follow events live from a saved cursor. The tail reconnects by itself
	// and returns an error only after 8 failed reconnects in a row.
	tail := client.TailEvents(ctx, lingara.EventsOptions{Cursor: savedCursor})
	defer tail.Close()
	for {
		ev, err := tail.Next(ctx)
		if err != nil {
			return err // resume later from tail.Cursor()
		}
		if ready, ok := ev.(lingara.LessonPlanReady); ok {
			fmt.Println("plan ready:", ready.Data.PlanID)
		}
	}
	// lingara:end
}
