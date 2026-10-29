package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"iter"
	"sync"
	"time"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

// observed is one call as the harness saw it, in the contract's vocabulary.
type observed struct {
	outcome       string
	status        any
	body          any
	events        []any
	variant       string
	fields        map[string]any
	servedVersion any
	sleeps        []int
	hooks         []any
	// renderings is every rendering of the client, of a returned error and
	// of a completed call's result.
	renderings []string
	// What an events or tail step yielded (events.go).
	eventIDs     []any
	unknownTypes []any
	cursor       string
}

// runStep runs one call, n times at once for `parallel: n`, and compares
// each run with the step's expectation.
func runStep(r *rig, call, expect map[string]any) []string {
	r.reset()
	n := 1
	if p, ok := call["parallel"].(float64); ok {
		n = int(p)
	}
	runs := make([]observed, n)
	var wg sync.WaitGroup
	for i := range runs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			runs[i] = invoke(r, call)
		}()
	}
	wg.Wait()
	operation, _ := call["operation"].(string)
	var mismatches []string
	for i, seen := range runs {
		seen.sleeps, seen.hooks = r.sleepsS(), r.hookCalls()
		for _, verb := range []string{"%v", "%+v", "%#v", "%s"} {
			seen.renderings = append(seen.renderings, fmt.Sprintf(verb, r.client))
		}
		label := ""
		if n > 1 {
			label = fmt.Sprintf("call %d: ", i+1)
		}
		for _, m := range compare(expect, seen) {
			mismatches = append(mismatches, operation+": "+label+m)
		}
	}
	return mismatches
}

func invoke(r *rig, call map[string]any) observed {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	c := r.client
	params, _ := call["params"].(map[string]any)
	id, _ := params["id"].(string)
	cancelAfter := -1
	if n, ok := call["cancel_after_events"].(float64); ok {
		cancelAfter = int(n)
	}
	drain := func(events iter.Seq2[any, error], served string, err error) observed {
		return consume(events, served, err, cancelAfter, cancel)
	}
	switch operation, _ := call["operation"].(string); operation {
	case "generateVocabulary":
		s, err := c.GenerateVocabulary(ctx, body[lingara.VocabRequest](call))
		return drain(stream(s, err))
	case "createLessonPlan":
		s, err := c.CreateLessonPlan(ctx, body[lingara.LessonPlanCreateRequest](call))
		return drain(stream(s, err))
	case "streamLessonPlan":
		s, err := c.StreamLessonPlan(ctx, id)
		return drain(stream(s, err))
	case "sendTutorMessage":
		s, err := c.SendTutorMessage(ctx, body[lingara.TutorTurnRequest](call))
		return drain(stream(s, err))
	case "sendDialogueTurn":
		s, err := c.SendDialogueTurn(ctx, body[lingara.DialogueTurnRequest](call))
		return drain(stream(s, err))
	case "streamEvents":
		s, err := c.StreamEvents(ctx, streamEventsOptions(params))
		return drain(stream(s, err))
	default:
		return invokeJSON(ctx, c, operation, call)
	}
}

func invokeJSON(ctx context.Context, c *lingara.Client, operation string, call map[string]any) observed {
	params, _ := call["params"].(map[string]any)
	id, _ := params["id"].(string)
	switch operation {
	case "getLessonPlan":
		return result(c.GetLessonPlan(ctx, id))
	case "getUsage":
		return result(c.GetUsage(ctx))
	case "getOpenApiDocument":
		return result(c.GetOpenAPIDocument(ctx))
	case "getAsyncApiDocument":
		return result(c.GetAsyncAPIDocument(ctx))
	case "listApiVersions":
		return result(c.ListAPIVersions(ctx))
	case "getApiVersion":
		return result(c.GetAPIVersion(ctx, id))
	}
	return invokeWithInput(ctx, c, operation, call)
}

// invokeWithInput runs the JSON operations that take a body, query or a
// named path parameter.
func invokeWithInput(ctx context.Context, c *lingara.Client, operation string, call map[string]any) observed {
	params, _ := call["params"].(map[string]any)
	switch operation {
	case "listEvents":
		return result(c.ListEvents(ctx, listEventsOptions(params)))
	case "sendEvent":
		return sendEvent(ctx, c, call)
	case "createEmbedToken":
		return minted(c.CreateEmbedToken(ctx, body[lingara.EmbedTokenRequest](call)))
	case "deleteEmbedPlayer":
		return noContent(c.DeleteEmbedPlayer(ctx, playerRef(params)))
	case "linkEmbedPlayer":
		return result(c.LinkEmbedPlayer(ctx, playerRef(params)))
	case "unlinkEmbedPlayer":
		return noContent(c.UnlinkEmbedPlayer(ctx, playerRef(params)))
	}
	return invokeCanvas(ctx, c, operation, call)
}

// invokeCanvas runs the seven canvas operations on client.Canvases() (ADR
// 16.10.26ak); params.canvas_id is the path id.
func invokeCanvas(ctx context.Context, c *lingara.Client, operation string, call map[string]any) observed {
	params, _ := call["params"].(map[string]any)
	id, _ := params["canvas_id"].(string)
	canvases := c.Canvases()
	switch operation {
	case "listCanvases":
		cursor, _ := params["cursor"].(string)
		return result(canvases.ListCanvases(ctx, lingara.ListCanvasesOptions{Cursor: cursor, Filters: canvasFilters(params)}))
	case "createCanvas":
		return result(canvases.CreateCanvas(ctx, body[lingara.CreateCanvasRequest](call)))
	case "getCanvas":
		return result(canvases.GetCanvas(ctx, id))
	case "renameCanvas":
		return result(canvases.RenameCanvas(ctx, id, body[lingara.RenameCanvasRequest](call)))
	case "saveCanvasDraft":
		return result(canvases.SaveCanvasDraft(ctx, id, body[lingara.SaveCanvasDraftRequest](call)))
	case "activateCanvas":
		return result(canvases.ActivateCanvas(ctx, id, body[lingara.ActivateCanvasRequest](call)))
	case "dryRunCanvasPlan":
		return result(canvases.DryRunCanvasPlan(ctx, body[lingara.CanvasPlan](call)))
	}
	return invokeDialogue(ctx, c, operation, call)
}

// invokeDialogue runs the seven dialogue operations on client.Dialogues()
// (ADR 29.10.26c); params.dialogue_id is the path id.
func invokeDialogue(ctx context.Context, c *lingara.Client, operation string, call map[string]any) observed {
	params, _ := call["params"].(map[string]any)
	id, _ := params["dialogue_id"].(string)
	dialogues := c.Dialogues()
	switch operation {
	case "listDialogues":
		cursor, _ := params["cursor"].(string)
		return result(dialogues.ListDialogues(ctx, lingara.ListDialoguesOptions{Cursor: cursor, Filters: dialogueFilters(params)}))
	case "createDialogue":
		return result(dialogues.CreateDialogue(ctx, body[lingara.CreateDialogueRequest](call)))
	case "getDialogue":
		return result(dialogues.GetDialogue(ctx, id))
	case "renameDialogue":
		return result(dialogues.RenameDialogue(ctx, id, body[lingara.RenameDialogueRequest](call)))
	case "saveDialogueDraft":
		return result(dialogues.SaveDialogueDraft(ctx, id, body[lingara.SaveDialogueDraftRequest](call)))
	case "activateDialogue":
		return result(dialogues.ActivateDialogue(ctx, id, body[lingara.ActivateDialogueRequest](call)))
	case "exportDialogue":
		revision, _ := params["revision"].(string)
		layout, _ := params["layout"].(string)
		ifNoneMatch, _ := params["if_none_match"].(string)
		return exported(dialogues.ExportDialogue(ctx, id, lingara.ExportDialogueOptions{
			Revision: lingara.ExportDialogueParamsRevision(revision), Layout: lingara.ExportDialogueParamsLayout(layout), IfNoneMatch: ifNoneMatch,
		}))
	}
	return observed{outcome: "harness: no operation " + operation}
}

// exported reports an export as D7's object under `body`: {revision, etag,
// ir_json} for a 200, or {not_modified: true, revision, etag} for a 304.
func exported(res *lingara.Result[lingara.ExportedDialogue], err error) observed {
	if err != nil {
		return failed(err, nil, nil)
	}
	seen := observed{outcome: "completed", servedVersion: orNil(res.ServedVersion), renderings: rendered(res)}
	switch v := res.Value.(type) {
	case lingara.ExportedDialogueBody:
		seen.status, seen.body = 200, map[string]any{"revision": v.Revision, "etag": v.ETag, "ir_json": v.IRJSON}
	case lingara.ExportedDialogueNotModified:
		seen.status, seen.body = 304, map[string]any{"not_modified": true, "revision": v.Revision, "etag": v.ETag}
	}
	return seen
}

// playerRef is a call's player_ref path parameter.
func playerRef(params map[string]any) string {
	ref, _ := params["player_ref"].(string)
	return ref
}

// body decodes a call's body into the operation's generated request type.
func body[T any](call map[string]any) T {
	var v T
	raw, _ := json.Marshal(call["body"])
	_ = json.Unmarshal(raw, &v)
	return v
}

func result[T any](res *lingara.Result[T], err error) observed {
	if err != nil {
		return failed(err, nil, nil)
	}
	return observed{outcome: "completed", status: 200, body: res.Value, servedVersion: orNil(res.ServedVersion), renderings: rendered(res)}
}

// noContent is a bodiless 204 result (ADR 1.10.26w D4).
func noContent(res *lingara.Result[struct{}], err error) observed {
	if err != nil {
		return failed(err, nil, nil)
	}
	return observed{outcome: "completed", status: 204, servedVersion: orNil(res.ServedVersion), renderings: rendered(res)}
}

// minted reports a MintedToken's body in wire form, read through the
// exposing accessor: snake_case keys, expires_at as received and expires_in
// in whole seconds. Its renderings are the result's own, which redact.
func minted(res *lingara.Result[lingara.MintedToken], err error) observed {
	if err != nil {
		return failed(err, nil, nil)
	}
	m := res.Value
	wire := map[string]any{
		"token": m.Token.ExposeSecret(), "expires_at": m.ExpiresAt.Format(time.RFC3339), "expires_in": int64(m.ExpiresIn / time.Second),
		"subject": m.Subject, "scopes": m.Scopes, "account_linked": m.AccountLinked,
	}
	return observed{outcome: "completed", status: 200, body: wire, servedVersion: orNil(res.ServedVersion), renderings: rendered(res)}
}

// rendered is every rendering of a completed call's result, for the
// `redacted` scan (conformance/README.md; ADR 1.10.26w D7).
func rendered(v any) []string {
	var out []string
	for _, verb := range []string{"%v", "%+v", "%#v", "%s"} {
		out = append(out, fmt.Sprintf(verb, v))
	}
	if raw, err := json.Marshal(v); err == nil {
		out = append(out, string(raw))
	}
	return out
}

// stream erases a stream's event type, so one consume serves all four.
func stream[E any](s *lingara.Stream[E], err error) (iter.Seq2[any, error], string, error) {
	if err != nil {
		return nil, "", err
	}
	return func(yield func(any, error) bool) {
		defer s.Close()
		for ev, err := range s.Events() {
			if !yield(ev, err) {
				return
			}
		}
	}, s.ServedVersion(), nil
}

// consume drains a stream; after cancelAfter events it cancels the call's
// context, Go's native cancellation, and stops.
func consume(events iter.Seq2[any, error], served string, openErr error, cancelAfter int, cancel context.CancelFunc) observed {
	if openErr != nil {
		return failed(openErr, nil, nil)
	}
	var seen []any
	for ev, err := range events {
		if err != nil {
			return failed(err, seen, orNil(served))
		}
		seen = append(seen, ev)
		if len(seen) == cancelAfter {
			cancel()
			return observed{outcome: "cancelled", events: seen, servedVersion: orNil(served)}
		}
	}
	return observed{outcome: "completed", status: 200, events: seen, servedVersion: orNil(served)}
}

func failed(err error, events []any, served any) observed {
	o := observed{outcome: "error", events: events, servedVersion: served}
	if errors.Is(err, context.Canceled) {
		o.outcome = "cancelled"
	}
	o.variant, o.fields = errorFields(err)
	for e := err; e != nil; e = errors.Unwrap(e) {
		for _, verb := range []string{"%v", "%+v", "%#v", "%s"} {
			o.renderings = append(o.renderings, fmt.Sprintf(verb, e))
		}
	}
	return o
}

// errorFields is the contract's variant name and snake_case fields.
func errorFields(err error) (string, map[string]any) {
	var api *lingara.APIError
	var oauth *lingara.OAuthError
	var maintenance *lingara.MaintenanceError
	var transport *lingara.TransportError
	switch {
	case errors.As(err, &api):
		return "ApiError", map[string]any{"status": api.Status, "code": api.Code, "message": api.Message,
			"retry_after": seconds(api.RetryAfter), "plan_id": orNil(api.PlanID), "served_version": orNil(api.ServedVersion)}
	case errors.As(err, &oauth):
		return "OAuthError", map[string]any{"status": oauth.Status, "error": oauth.ErrorCode,
			"description": orNil(oauth.Description), "retry_after": seconds(oauth.RetryAfter)}
	case errors.As(err, &maintenance):
		return "MaintenanceError", map[string]any{"body": maintenance.Body, "retry_after": seconds(maintenance.RetryAfter)}
	case errors.As(err, &transport):
		return "TransportError", map[string]any{"kind": string(transport.Kind)}
	}
	return "not a known variant", map[string]any{"debug": err.Error()}
}

func seconds(d *time.Duration) any {
	if d == nil {
		return nil
	}
	return int64(d.Seconds())
}
