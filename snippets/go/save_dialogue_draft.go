package snippets

import (
	"context"
	"errors"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func saveDialogueDraft(ctx context.Context, client *lingara.Client, definition lingara.DialogueDefinition, draftVersion int32) error {
	// lingara:begin saveDialogueDraft
	const dialogueID = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71"
	dialogues := client.Dialogues()
	saved, err := dialogues.SaveDialogueDraft(ctx, dialogueID, lingara.SaveDialogueDraftRequest{Definition: definition, DraftVersion: draftVersion})
	var apiErr *lingara.APIError
	if errors.As(err, &apiErr) && apiErr.Code == "dialogue_conflict" {
		// The draft moved since you read it, perhaps by your own retried
		// save. It is never retried for you: re-read, compare, and decide.
		// With no draft at all, send DraftVersion 0 to open one.
		current, err := dialogues.GetDialogue(ctx, dialogueID)
		if err != nil {
			return err
		}
		var now int32
		if current.Value.Draft != nil {
			now = current.Value.Draft.DraftVersion
		}
		fmt.Println("changed elsewhere; the draft is now at", now)
		return nil
	}
	if err != nil {
		return err
	}
	fmt.Println("saved; the next save sends", saved.Value.Draft.DraftVersion)
	// lingara:end
	return nil
}
