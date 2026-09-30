package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

// client needs no credentials here, since this operation needs no token:
// lingara.New() is enough.
func getOpenAPIDocument(ctx context.Context, client *lingara.Client) error {
	// lingara:begin getOpenApiDocument
	doc, err := client.GetOpenAPIDocument(ctx)
	if err != nil {
		return err
	}
	fmt.Println(doc.Value["openapi"], doc.ServedVersion)
	// lingara:end
	return nil
}
