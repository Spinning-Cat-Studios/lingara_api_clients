package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func activateDialogue(ctx context.Context, client *lingara.Client) error {
	// lingara:begin activateDialogue
	// Promote the draft you read: a stale or missing draft is 409
	// dialogue_conflict and writes nothing.
	const dialogueID = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71"
	dialogues := client.Dialogues()
	current, err := dialogues.GetDialogue(ctx, dialogueID)
	if err != nil || current.Value.Draft == nil {
		return err
	}
	activated, err := dialogues.ActivateDialogue(ctx, dialogueID, lingara.ActivateDialogueRequest{DraftVersion: current.Value.Draft.DraftVersion})
	if err != nil {
		return err
	}
	fmt.Println("active revision", activated.Value.Active.Revision)
	// lingara:end
	return nil
}
