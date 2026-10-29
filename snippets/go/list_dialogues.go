package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func listDialogues(ctx context.Context, client *lingara.Client) error {
	// lingara:begin listDialogues
	// One page; pass its NextCursor back as Cursor for the next, or let
	// PageDialogues walk them all.
	page, err := client.Dialogues().ListDialogues(ctx, lingara.ListDialoguesOptions{
		Filters: lingara.DialogueFilters{Name: "market", Limit: 20},
	})
	if err != nil {
		return err
	}
	for _, d := range page.Value.Items {
		fmt.Println(d.ID, d.Name, d.IrID, d.NpcCount, "NPCs", d.CheckpointCount, "checkpoints")
	}
	if page.Value.HasMore {
		fmt.Println("more from", *page.Value.NextCursor)
	}
	// lingara:end
	return nil
}
