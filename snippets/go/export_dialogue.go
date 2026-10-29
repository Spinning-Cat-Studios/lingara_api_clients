package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

// cachedDialogue is the export a game holds between calls.
type cachedDialogue struct {
	etag, irJSON string
}

func exportDialogue(ctx context.Context, client *lingara.Client, cached *cachedDialogue) error {
	// lingara:begin exportDialogue
	// Cache by ETag: the body is canonical bytes, so store it exactly as
	// received. A 304 still spends one unit of the rate budget.
	exported, err := client.Dialogues().ExportDialogue(ctx, "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71",
		lingara.ExportDialogueOptions{IfNoneMatch: cached.etag})
	if err != nil {
		return err
	}
	switch v := exported.Value.(type) {
	case lingara.ExportedDialogueBody:
		*cached = cachedDialogue{etag: v.ETag, irJSON: v.IRJSON}
		fmt.Println("revision", v.Revision, "is new")
	case lingara.ExportedDialogueNotModified:
		fmt.Println("revision", v.Revision, "is unchanged")
	}
	// lingara:end
	return nil
}
