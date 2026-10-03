package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func sendEvent(ctx context.Context, client *lingara.Client) error {
	// lingara:begin sendEvent
	generate := true
	event := lingara.InboundWorldContextChanged(lingara.WorldContextChanged{
		Scene: "A night market after rain", SourceLang: "en", TargetLang: "zh", Level: 2, Generate: &generate,
	})
	// A key of your own makes a resend after a crash safe; without one, the
	// library generates a key for this call.
	accepted, err := client.SendEvent(ctx, event, lingara.WithIdempotencyKey("game-save-17/scene-4"))
	if err != nil {
		return err
	}
	if r := accepted.Value.Reaction; r != nil && r.PlanStatus != nil && *r.PlanStatus == lingara.PlanStatusGenerating {
		fmt.Println("lesson_plan.ready or .failed will follow for plan", *r.PlanID)
	}
	// lingara:end
	return nil
}
