package lingara

import (
	"context"
	"errors"
	"net/http"
	"reflect"
	"sync/atomic"
	"testing"
	"time"
)

// retryCase is one /v1 refusal the client meets on every attempt: its
// status, the Retry-After header the n-th attempt carries ("" for none), and
// what the client should do with it.
type retryCase struct {
	name       string
	status     int
	header     func(n int32, now time.Time) string
	calls      int32
	sleeps     []time.Duration
	retryAfter *time.Duration
}

func seconds(n int) *time.Duration {
	d := time.Duration(n) * time.Second
	return &d
}

// runRetryCase serves the case, calls a public operation once, and checks
// the attempts, the sleeps and the error's RetryAfter.
func runRetryCase(t *testing.T, c retryCase) {
	t.Helper()
	var calls atomic.Int32
	clock, sleeper := newFakeClock(), &sleepRecorder{}
	srv := newServer(t, nil, func(w http.ResponseWriter, _ *http.Request) {
		if v := c.header(calls.Add(1), clock.Now()); v != "" {
			w.Header().Set("Retry-After", v)
		}
		jsonAnswer(w, c.status, `{"code":"rate_limited","error":"Slow down."}`)
	})
	client, err := New(WithBaseURL(srv.URL), WithClock(clock.Now), WithSleeper(sleeper.Sleep))
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.GetOpenAPIDocument(context.Background())
	var api *APIError
	if !errors.As(err, &api) || api.Status != c.status {
		t.Fatalf("%s: got %v, want an APIError with status %d", c.name, err, c.status)
	}
	if calls.Load() != c.calls || !reflect.DeepEqual(sleeper.Recorded(), c.sleeps) {
		t.Errorf("%s: %d attempts and sleeps %v, want %d and %v", c.name, calls.Load(), sleeper.Recorded(), c.calls, c.sleeps)
	}
	if !reflect.DeepEqual(api.RetryAfter, c.retryAfter) {
		t.Errorf("%s: RetryAfter %s, want %s", c.name, durationText(api.RetryAfter), durationText(c.retryAfter))
	}
}

// TestRetryAfterCapMissingHeaderDateAndExhaustion: 29.9.26q AC10. A
// Retry-After above the cap raises at once with RetryAfter set; a missing
// one raises at once; an HTTP-date is read against the clock; three 429s
// raise after two sleeps.
func TestRetryAfterCapMissingHeaderDateAndExhaustion(t *testing.T) {
	always := func(v string) func(int32, time.Time) string { return func(int32, time.Time) string { return v } }
	// An HTTP-date has whole seconds, so this one is 29 s after the virtual
	// clock, and days in the past by real time: only a read against the
	// clock sleeps 29 s. The second attempt carries none, so it is raised.
	date := func(n int32, now time.Time) string {
		if n > 1 {
			return ""
		}
		return now.Add(29500 * time.Millisecond).UTC().Format(http.TimeFormat)
	}
	for _, c := range []retryCase{
		{"over the cap", 429, always("120"), 1, nil, seconds(120)},
		{"no Retry-After", 429, always(""), 1, nil, nil},
		{"an HTTP-date", 503, date, 2, []time.Duration{29 * time.Second}, nil},
		{"three 429s", 429, always("1"), 3, []time.Duration{time.Second, time.Second}, seconds(1)},
	} {
		runRetryCase(t, c)
	}
}

// TestRetryWaitHonoursContextCancel: 29.9.26q AC11. A cancelled context
// interrupts a Retry-After wait and returns context.Canceled, not a K3 type.
func TestRetryWaitHonoursContextCancel(t *testing.T) {
	srv := newServer(t, nil, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Retry-After", "30")
		jsonAnswer(w, 429, `{"code":"rate_limited","error":"Slow down."}`)
	})
	c, err := New(WithBaseURL(srv.URL)) // the real sleeper
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	time.AfterFunc(50*time.Millisecond, cancel)
	started := time.Now()
	_, err = c.GetOpenAPIDocument(ctx)
	var lingaraErr Error
	if !errors.Is(err, context.Canceled) || errors.As(err, &lingaraErr) {
		t.Fatalf("got %v, want context.Canceled and no K3 type", err)
	}
	if elapsed := time.Since(started); elapsed > 5*time.Second {
		t.Fatalf("the cancel took %v to end the 30 s wait", elapsed)
	}
}
