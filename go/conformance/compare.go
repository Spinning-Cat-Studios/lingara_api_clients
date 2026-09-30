package main

import (
	"encoding/json"
	"fmt"
	"strings"
)

// How an observed call is compared with a case's expect (conformance/
// README.md, Comparison rules). Pure: no I/O, no library import.

// canon is JSON with null-valued keys dropped and object keys sorted, after
// a round trip, so an int and a float64 of the same number compare equal.
func canon(v any) string {
	raw, err := json.Marshal(v)
	if err != nil {
		return fmt.Sprintf("<unencodable: %v>", err)
	}
	var generic any
	_ = json.Unmarshal(raw, &generic)
	out, _ := json.Marshal(normalise(generic))
	return string(out)
}

func normalise(v any) any {
	switch t := v.(type) {
	case []any:
		out := make([]any, len(t))
		for i, item := range t {
			out[i] = normalise(item)
		}
		return out
	case map[string]any:
		out := map[string]any{}
		for k, item := range t {
			if item != nil {
				out[k] = normalise(item)
			}
		}
		return out
	}
	return v
}

// substitute replaces {base_url} in every string of an expected value.
func substitute(v any, baseURL string) any {
	switch t := v.(type) {
	case string:
		return strings.ReplaceAll(t, "{base_url}", baseURL)
	case []any:
		out := make([]any, len(t))
		for i, item := range t {
			out[i] = substitute(item, baseURL)
		}
		return out
	case map[string]any:
		out := map[string]any{}
		for k, item := range t {
			out[k] = substitute(item, baseURL)
		}
		return out
	}
	return v
}

func same(label string, want, got any, out []string) []string {
	if w, g := canon(want), canon(got); w != g {
		return append(out, fmt.Sprintf("%s: expected %s, got %s", label, w, g))
	}
	return out
}

// compare returns every difference between one observed call and its
// expectation.
func compare(expect map[string]any, seen observed) []string {
	var out []string
	if want, _ := expect["outcome"].(string); seen.outcome != want {
		detail := ""
		if seen.variant != "" {
			detail = fmt.Sprintf(" (%s %s)", seen.variant, canon(seen.fields))
		}
		out = append(out, fmt.Sprintf("outcome: expected %s, got %s%s", want, seen.outcome, detail))
	}
	status := seen.status
	if status == nil && seen.fields != nil {
		status = seen.fields["status"]
	}
	events := seen.events
	if events == nil {
		events = []any{}
	}
	for _, pair := range []struct {
		label string
		got   any
	}{
		{"status", status}, {"body", seen.body}, {"events", events},
		{"served_version", seen.servedVersion}, {"sleeps_s", seen.sleeps}, {"hook_calls", orEmpty(seen.hooks)},
	} {
		if want, ok := expect[pair.label]; ok {
			out = same(pair.label, want, pair.got, out)
		}
	}
	if want, ok := expect["error"].(map[string]any); ok {
		out = compareError(want, seen, out)
	}
	return compareRedacted(expect, seen, out)
}

func orEmpty(v []any) []any {
	if v == nil {
		return []any{}
	}
	return v
}

func compareError(want map[string]any, seen observed, out []string) []string {
	variant, _ := want["variant"].(string)
	if seen.variant == "" {
		return append(out, fmt.Sprintf("error: expected %s, got none", variant))
	}
	if seen.variant != variant {
		out = append(out, fmt.Sprintf("error.variant: expected %s, got %s", variant, seen.variant))
	}
	fields, _ := want["fields"].(map[string]any)
	for name, value := range fields {
		out = same("error."+name, value, seen.fields[name], out)
	}
	return out
}

func compareRedacted(expect map[string]any, seen observed, out []string) []string {
	secrets, _ := expect["redacted"].([]any)
	for _, raw := range secrets {
		secret, _ := raw.(string)
		for _, r := range seen.renderings {
			if secret != "" && strings.Contains(r, secret) {
				out = append(out, fmt.Sprintf("redacted: a rendering contains %.12s…", secret))
				break
			}
		}
	}
	return out
}
