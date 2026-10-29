package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

// The events steps and operations (conformance/README.md; ADR 30.9.26aa D9):
// `events` iterates client.Events to its end, `tail` takes `take` events from
// client.TailEvents and then closes it, and sendEvent builds its
// InboundEvent from the case's body through the public constructors. The
// page_canvases step drives the canvas page helper the same way (ADR
// 16.10.26ak D7), and the page_dialogues step the dialogue one (ADR
// 29.10.26c D7).

// helperOptions reads an events or tail step's cursor, start and types.
func helperOptions(step map[string]any) lingara.EventsOptions {
	cursor, _ := step["cursor"].(string)
	start, _ := step["start"].(string)
	types, _ := step["types"].([]any)
	return lingara.EventsOptions{Cursor: cursor, Start: lingara.EventStart(start), Types: texts(types)}
}

func listEventsOptions(params map[string]any) lingara.ListEventsOptions {
	opts := helperOptions(params)
	limit, _ := params["limit"].(float64)
	return lingara.ListEventsOptions{Cursor: opts.Cursor, Start: opts.Start, Types: opts.Types, Limit: int(limit)}
}

func streamEventsOptions(params map[string]any) lingara.StreamEventsOptions {
	opts := helperOptions(params)
	last, _ := params["last_event_id"].(string)
	return lingara.StreamEventsOptions{LastEventID: last, Cursor: opts.Cursor, Start: opts.Start, Types: opts.Types}
}

// sendEvent builds the InboundEvent the case's body names; sendEvent's
// success is a 202.
func sendEvent(ctx context.Context, c *lingara.Client, call map[string]any) observed {
	event, err := inboundEvent(call["body"])
	if err != nil {
		return observed{outcome: "harness: " + err.Error()}
	}
	var opts []lingara.SendEventOption
	if key, ok := call["idempotency_key"].(string); ok {
		opts = append(opts, lingara.WithIdempotencyKey(key))
	}
	res, err := c.SendEvent(ctx, event, opts...)
	if err != nil {
		return failed(err, nil, nil)
	}
	return observed{outcome: "completed", status: 202, body: res.Value, servedVersion: orNil(res.ServedVersion)}
}

func inboundEvent(body any) (lingara.InboundEvent, error) {
	fields, _ := body.(map[string]any)
	raw, _ := json.Marshal(fields["data"])
	switch eventType, _ := fields["type"].(string); eventType {
	case "world.context_changed":
		var data lingara.WorldContextChanged
		err := json.Unmarshal(raw, &data)
		return lingara.InboundWorldContextChanged(data), err
	case "world.practice_requested":
		var data lingara.WorldPracticeRequested
		err := json.Unmarshal(raw, &data)
		return lingara.InboundWorldPracticeRequested(data), err
	default:
		return lingara.InboundEvent{}, fmt.Errorf("no inbound event %q", eventType)
	}
}

// eventIterator is what the two helpers share.
type eventIterator interface {
	Next(ctx context.Context) (lingara.Event, error)
	Cursor() string
}

// runHelper runs one events or tail step and compares it with its expect.
func runHelper(r *rig, kind string, step, expect map[string]any) []string {
	r.reset()
	ctx := context.Background()
	opts := helperOptions(step)
	var seen observed
	if kind == "tail" {
		take, _ := step["take"].(float64)
		tail := r.client.TailEvents(ctx, opts)
		seen = iterate(ctx, tail, int(take))
		_ = tail.Close()
	} else {
		seen = iterate(ctx, r.client.Events(ctx, opts), -1)
	}
	seen.sleeps = r.sleepsS()
	var mismatches []string
	for _, m := range compare(expect, seen) {
		mismatches = append(mismatches, kind+": "+m)
	}
	return mismatches
}

// iterate calls Next until the end, an error, or take events (-1: no limit).
func iterate(ctx context.Context, it eventIterator, take int) observed {
	seen := observed{eventIDs: []any{}, unknownTypes: []any{}}
	for len(seen.eventIDs) != take {
		ev, err := it.Next(ctx)
		if errors.Is(err, lingara.ErrNoMoreEvents) {
			break
		}
		if err != nil {
			failure := failed(err, nil, nil)
			failure.eventIDs, failure.unknownTypes, failure.cursor = seen.eventIDs, seen.unknownTypes, it.Cursor()
			return failure
		}
		seen.eventIDs = append(seen.eventIDs, ev.Meta().ID)
		if unknown, ok := ev.(lingara.UnknownEvent); ok {
			seen.unknownTypes = append(seen.unknownTypes, unknown.Type)
		}
	}
	seen.outcome, seen.cursor = "completed", it.Cursor()
	return seen
}

// canvasFilters reads listCanvases' or a page_canvases step's params, less
// the cursor (ADR 16.10.26ak).
func canvasFilters(params map[string]any) lingara.CanvasFilters {
	limit, _ := params["limit"].(float64)
	name, _ := params["name"].(string)
	status, _ := params["status"].(string)
	list := func(key string) []string {
		values, _ := params[key].([]any)
		return texts(values)
	}
	return lingara.CanvasFilters{
		Limit: int(limit), Name: name, Status: lingara.ListCanvasesParamsStatus(status),
		Recipes: list("recipes"), Kinds: list("kinds"), Scopes: list("scopes"),
	}
}

// runPageCanvases runs a page_canvases step (ADR 16.10.26ak D7): the helper
// iterated to its end, its items reported as one list under `body`, or its
// error.
func runPageCanvases(r *rig, step, expect map[string]any) []string {
	r.reset()
	params, _ := step["params"].(map[string]any)
	seen := observed{outcome: "completed"}
	items := []any{}
	for item, err := range r.client.Canvases().PageCanvases(context.Background(), canvasFilters(params)) {
		if err != nil {
			seen = failed(err, nil, nil)
			break
		}
		items = append(items, item)
	}
	if seen.outcome == "completed" {
		seen.body = items
	}
	seen.sleeps = r.sleepsS()
	var mismatches []string
	for _, m := range compare(expect, seen) {
		mismatches = append(mismatches, "page_canvases: "+m)
	}
	return mismatches
}

// dialogueFilters reads listDialogues' or a page_dialogues step's params,
// less the cursor (ADR 29.10.26c).
func dialogueFilters(params map[string]any) lingara.DialogueFilters {
	limit, _ := params["limit"].(float64)
	name, _ := params["name"].(string)
	return lingara.DialogueFilters{Limit: int(limit), Name: name}
}

// runPageDialogues runs a page_dialogues step (ADR 29.10.26c D7): the helper
// iterated to its end, its items reported as one list under `body`, or its
// error.
func runPageDialogues(r *rig, step, expect map[string]any) []string {
	r.reset()
	params, _ := step["params"].(map[string]any)
	seen := observed{outcome: "completed"}
	items := []any{}
	for item, err := range r.client.Dialogues().PageDialogues(context.Background(), dialogueFilters(params)) {
		if err != nil {
			seen = failed(err, nil, nil)
			break
		}
		items = append(items, item)
	}
	if seen.outcome == "completed" {
		seen.body = items
	}
	seen.sleeps = r.sleepsS()
	var mismatches []string
	for _, m := range compare(expect, seen) {
		mismatches = append(mismatches, "page_dialogues: "+m)
	}
	return mismatches
}

// compareHelper checks the three helper fields: event_ids, unknown_types and
// the cursor matcher.
func compareHelper(expect map[string]any, seen observed, out []string) []string {
	if want, ok := expect["event_ids"]; ok {
		out = same("event_ids", want, orEmpty(seen.eventIDs), out)
	}
	if want, ok := expect["unknown_types"]; ok {
		out = same("unknown_types", want, orEmpty(seen.unknownTypes), out)
	}
	if want, ok := expect["cursor"].(map[string]any); ok && !matches(want, seen.cursor) {
		out = append(out, fmt.Sprintf("cursor: expected %s, got %q", canon(want), seen.cursor))
	}
	return out
}

// matches applies a string matcher: equals, prefix, contains or pattern.
func matches(m map[string]any, got string) bool {
	for op, raw := range m {
		want, _ := raw.(string)
		switch op {
		case "equals":
			return got == want
		case "prefix":
			return strings.HasPrefix(got, want)
		case "contains":
			return strings.Contains(got, want)
		case "pattern":
			re, err := regexp.Compile(want)
			return err == nil && re.MatchString(got)
		}
	}
	return false
}
