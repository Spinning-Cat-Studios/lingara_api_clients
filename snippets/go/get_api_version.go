package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

// client needs no credentials here, since this operation needs no token:
// lingara.New() is enough.
func getAPIVersion(ctx context.Context, client *lingara.Client) error {
	// lingara:begin getApiVersion
	version, err := client.GetAPIVersion(ctx, "2026-09-knowing-tenpounder")
	if err != nil {
		return err
	}
	fmt.Println(version.Value.ID, version.Value.State)
	// lingara:end
	return nil
}
