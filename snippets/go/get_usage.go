package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func getUsage(ctx context.Context, client *lingara.Client) error {
	// lingara:begin getUsage
	usage, err := client.GetUsage(ctx)
	if err != nil {
		return err
	}
	for _, row := range usage.Value.Allowance {
		fmt.Printf("%s (%s): %d of %d left\n", row.Feature, row.Window, row.Remaining, row.Limit)
	}
	// lingara:end
	return nil
}
