package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func dryRunCanvasPlan(ctx context.Context, client *lingara.Client) error {
	// lingara:begin dryRunCanvasPlan
	plan := lingara.CanvasPlan{Ops: []map[string]any{
		{"handle": "c1", "kind": "client", "op": "create", "body": map[string]any{"name": "Lesson server", "scopes": []string{"events:read"}}},
	}}
	run, err := client.Canvases().DryRunCanvasPlan(ctx, plan)
	if err != nil {
		return err
	}
	// A refused op is an answer, not an error: Ok is false. Branch on Code;
	// Error is English, for a log line or a fallback.
	for _, v := range run.Value.Verdicts {
		if v.Verdict == lingara.CanvasVerdictKindRefused && v.Code != nil {
			fmt.Println(v.Handle, "refused:", *v.Code)
		} else {
			fmt.Println(v.Handle, v.Verdict)
		}
	}
	// lingara:end
	return nil
}
