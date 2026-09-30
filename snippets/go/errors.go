package snippets

import (
	"context"
	"errors"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func handleErrors(ctx context.Context, client *lingara.Client) {
	// lingara:begin errors
	_, err := client.GetUsage(ctx)
	var apiErr *lingara.APIError
	var oauthErr *lingara.OAuthError
	var maintenance *lingara.MaintenanceError
	var transport *lingara.TransportError
	switch {
	case err == nil:
	case errors.As(err, &apiErr):
		// A refusal from the API: Status, Code (stable) and Message (localised).
		fmt.Println(apiErr.Status, apiErr.Code, apiErr.Message)
		if apiErr.RetryAfter != nil {
			fmt.Println("retry after", *apiErr.RetryAfter)
		}
	case errors.As(err, &oauthErr):
		// The token endpoint refused the credentials or the scopes.
		fmt.Println(oauthErr.Status, oauthErr.ErrorCode, oauthErr.Description)
	case errors.As(err, &maintenance):
		fmt.Println("under maintenance")
		if maintenance.RetryAfter != nil {
			fmt.Println("retry after", *maintenance.RetryAfter)
		}
	case errors.As(err, &transport):
		// No usable answer: connect, tls, reset, timeout, and so on.
		fmt.Println("transport:", transport.Kind)
	default:
		// ctx was cancelled or timed out: errors.Is(err, context.Canceled).
		fmt.Println(err)
	}
	// lingara:end
}
