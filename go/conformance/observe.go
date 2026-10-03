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
	// renderings is every rendering of the client and of a returned error.
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
	case "streamEvents":
		s, err := c.StreamEvents(ctx, streamEventsOptions(params))
		return drain(stream(s, err))
	case "listEvents":
		return result(c.ListEvents(ctx, listEventsOptions(params)))
	case "sendEvent":
		return sendEvent(ctx, c, call)
	default:
		return invokeJSON(ctx, c, operation, id)
	}
}

func invokeJSON(ctx context.Context, c *lingara.Client, operation, id string) observed {
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
	return observed{outcome: "harness: no operation " + operation}
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
	return observed{outcome: "completed", status: 200, body: res.Value, servedVersion: orNil(res.ServedVersion)}
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
