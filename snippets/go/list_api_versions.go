package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

// client needs no credentials here, since this operation needs no token:
// lingara.New() is enough.
func listAPIVersions(ctx context.Context, client *lingara.Client) error {
	// lingara:begin listApiVersions
	versions, err := client.ListAPIVersions(ctx)
	if err != nil {
		return err
	}
	for _, v := range versions.Value.Versions {
		fmt.Println(v.ID, v.State, v.Lts)
	}
	// lingara:end
	return nil
}
