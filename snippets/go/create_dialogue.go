package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func createDialogue(ctx context.Context, client *lingara.Client) error {
	// lingara:begin createDialogue
	// The definition is a dialogue.v1 document; the server validates it on
	// every save. Warnings are advice, never a refusal.
	name := "Night market"
	definition := lingara.DialogueDefinition{
		"schema": "dialogue.v1",
		"id":     "night_market_keepers",
		"start":  "greet",
		"npcs":   []any{map[string]any{"id": "old_wei", "name": "Old Wei"}},
		"checkpoints": []any{map[string]any{
			"id": "greet", "goal": "Greet the stranger", "may_disclose": []any{},
			"edges": []any{map[string]any{"id": "thanked", "to": "success", "when": "the player says thank you"}},
		}},
		"facts": []any{},
	}
	created, err := client.Dialogues().CreateDialogue(ctx, lingara.CreateDialogueRequest{Name: &name, Definition: definition})
	if err != nil {
		return err
	}
	if draft := created.Value.Draft; draft != nil {
		fmt.Println(created.Value.ID, "draft", draft.DraftVersion)
	}
	for _, w := range created.Value.Warnings {
		fmt.Println("warning", w.Code, "at", w.Path)
	}
	// lingara:end
	return nil
}
