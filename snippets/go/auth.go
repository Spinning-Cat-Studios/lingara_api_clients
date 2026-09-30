// Package snippets holds the Go examples the documentation site shows. Each
// marked region is vendored at a released tag, and `go vet` in make test-go
// compiles every one.
package snippets

// lingara:begin auth
import (
	"os"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

// lingara:end

func newClient() (*lingara.Client, error) {
	// lingara:begin auth
	client, err := lingara.New(
		lingara.WithClientCredentials(os.Getenv("LINGARA_CLIENT_ID"), os.Getenv("LINGARA_CLIENT_SECRET")),
	)
	if err != nil {
		return nil, err
	}
	// lingara:end
	return client, nil
}
