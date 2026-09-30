package main

import (
	"context"
	"math"
	"sync"
	"sync/atomic"
	"time"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

// clockStart is where the virtual clock starts for every case (README,
// Comparison rules).
const clockStart = 1_790_000_000

// rig is one case's client, built from its client block through the public
// options only, with a virtual clock, a recording sleeper and, when the case
// asks, a recording deprecation hook.
type rig struct {
	client *lingara.Client
	now    atomic.Int64

	mu     sync.Mutex
	sleeps []time.Duration
	hooks  []any
}

func (r *rig) advance(seconds int64) { r.now.Add(seconds) }

// reset clears what one step records, before the step runs.
func (r *rig) reset() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.sleeps, r.hooks = nil, nil
}

func (r *rig) sleepsS() []int {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]int, len(r.sleeps))
	for i, d := range r.sleeps {
		out[i] = int(math.Round(d.Seconds()))
	}
	return out
}

func (r *rig) hookCalls() []any {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]any{}, r.hooks...)
}

func build(c map[string]any, base, token string) (*rig, error) {
	r := &rig{}
	r.now.Store(clockStart)
	opts := []lingara.Option{
		lingara.WithBaseURL(base),
		lingara.WithTokenURL(token),
		lingara.WithClock(func() time.Time { return time.Unix(r.now.Load(), 0) }),
		// Records each requested duration and returns at once.
		lingara.WithSleeper(func(_ context.Context, d time.Duration) error {
			r.mu.Lock()
			defer r.mu.Unlock()
			r.sleeps = append(r.sleeps, d)
			return nil
		}),
	}
	opts = append(opts, caseOptions(c)...)
	if c["deprecation_hook"] == "record" {
		opts = append(opts, lingara.WithDeprecationHook(func(n lingara.DeprecationNotice) {
			r.mu.Lock()
			defer r.mu.Unlock()
			r.hooks = append(r.hooks, hookRecord(n))
		}))
	}
	client, err := lingara.New(opts...)
	if err != nil {
		return nil, err
	}
	r.client = client
	return r, nil
}

// caseOptions maps the rest of the client block onto the public options.
func caseOptions(c map[string]any) []lingara.Option {
	var opts []lingara.Option
	if cred, ok := c["credentials"].(map[string]any); ok {
		id, _ := cred["client_id"].(string)
		secret, _ := cred["client_secret"].(string)
		opts = append(opts, lingara.WithClientCredentials(id, secret))
		if cred["auth"] == "post" {
			opts = append(opts, lingara.WithClientSecretPost())
		}
	}
	if scopes, ok := c["scopes"].([]any); ok {
		opts = append(opts, lingara.WithScopes(texts(scopes)...))
	}
	if version, ok := c["version"].(string); ok {
		opts = append(opts, lingara.WithVersion(version))
	}
	retries, _ := c["retries"].(map[string]any)
	if n, ok := retries["max_attempts"].(float64); ok {
		opts = append(opts, lingara.WithMaxAttempts(int(n)))
	}
	if s, ok := retries["retry_after_cap_s"].(float64); ok {
		opts = append(opts, lingara.WithRetryAfterCap(time.Duration(s)*time.Second))
	}
	if suffix, ok := c["user_agent_suffix"].(string); ok {
		opts = append(opts, lingara.WithUserAgentSuffix(suffix))
	}
	if ms, ok := c["stream_idle_timeout_ms"].(float64); ok {
		opts = append(opts, lingara.WithStreamIdleTimeout(time.Duration(ms)*time.Millisecond))
	}
	return opts
}

func texts(values []any) []string {
	out := make([]string, 0, len(values))
	for _, v := range values {
		if s, ok := v.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

func hookRecord(n lingara.DeprecationNotice) any {
	seconds := func(t *time.Time) any {
		if t == nil {
			return nil
		}
		return t.Unix()
	}
	var link any
	if n.Link != nil {
		var target any
		if n.Link.URL != nil {
			target = n.Link.URL.String()
		}
		link = map[string]any{"raw": n.Link.Raw, "target": target}
	}
	return map[string]any{"version": orNil(n.Version), "deprecated_at": seconds(n.DeprecatedAt), "sunset_at": seconds(n.SunsetAt), "link": link}
}

// orNil maps Go's "" for absent onto the contract's null.
func orNil(s string) any {
	if s == "" {
		return nil
	}
	return s
}
