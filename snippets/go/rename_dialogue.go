package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func renameDialogue(ctx context.Context, client *lingara.Client) error {
	// lingara:begin renameDialogue
	renamed, err := client.Dialogues().RenameDialogue(ctx, "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71",
		lingara.RenameDialogueRequest{Name: "Night market keepers"})
	if err != nil {
		return err
	}
	fmt.Println(renamed.Value.Name)
	// lingara:end
	return nil
}
