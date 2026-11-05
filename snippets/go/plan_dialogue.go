package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func planDialogue(ctx context.Context, client *lingara.Client, document lingara.DialogueDefinition) error {
	// lingara:begin planDialogue
	// The first plan of a Procedural dialogue. Inside the embed kits the kit
	// calls this for you. Never retried: each call spends one embed:play
	// unit, a keep too.
	res, err := client.PlanDialogue(ctx, lingara.DialoguePlanRequest{
		Document: document,
		Npc:      "ma_ren",
		Reason:   lingara.PlanReasonStart,
		IDPrefix: "p1",
	})
	if err != nil {
		return err
	}
	if answer := res.Value; answer.Status == "planned" {
		for _, beat := range answer.Fragment.Beats {
			fmt.Println(beat.ID, beat.Atom)
		}
	} else {
		// A keep is a result: your frontier stands (on a first plan, walk the seed).
		fmt.Println("kept:", *answer.Reason)
	}
	// lingara:end
	return nil
}
