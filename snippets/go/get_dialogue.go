package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func getDialogue(ctx context.Context, client *lingara.Client) error {
	// lingara:begin getDialogue
	// The draft and the active revision, inline; either may be nil.
	dialogue, err := client.Dialogues().GetDialogue(ctx, "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71")
	if err != nil {
		return err
	}
	if draft := dialogue.Value.Draft; draft != nil {
		fmt.Println("draft", draft.DraftVersion)
	}
	if active := dialogue.Value.Active; active != nil {
		fmt.Println("active revision", active.Revision, "since", active.ActivatedAt)
	}
	// lingara:end
	return nil
}
