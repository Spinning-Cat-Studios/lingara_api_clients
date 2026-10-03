package lingara

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"regexp"
	"sync/atomic"
	"testing"
	"time"
)

// vectorsPath is the shared vector file, from the repository root
// (conformance/README.md, Vectors).
const vectorsPath = "../conformance/vectors/webhook-signatures.json"

type vector struct {
	Name    string            `json:"name"`
	Secrets []string          `json:"secrets"`
	Headers map[string]string `json:"headers"`
	Body    string            `json:"body"`
	Now     int64             `json:"now"`
	Expect  struct {
		OK *struct {
			ID      string `json:"id"`
			Type    string `json:"type"`
			Unknown bool   `json:"unknown"`
		} `json:"ok"`
		Error   WebhookFailure `json:"error"`
		Refused bool           `json:"refused"`
	} `json:"expect"`
}

func readVectors(t *testing.T) []vector {
	t.Helper()
	raw, err := os.ReadFile(vectorsPath)
	if err != nil {
		t.Fatal(err)
	}
	var file struct {
		Vectors []vector `json:"vectors"`
	}
	if err := json.Unmarshal(raw, &file); err != nil {
		t.Fatal(err)
	}
	if len(file.Vectors) == 0 {
		t.Fatal("no vectors")
	}
	return file.Vectors
}

// TestEverySharedVectorVerifiesAsExpected: 30.9.26aa AC25, and AC45's half
// for Go. Every vector in conformance/vectors/webhook-signatures.json gives
// its expected result from Verify, with its now through the clock seam and
// its header names exactly as written (so mixed case reaches the verifier).
// VerifySignature passes every ok and malformed_payload vector and fails
// every other error vector with that vector's own reason.
func TestEverySharedVectorVerifiesAsExpected(t *testing.T) {
	for _, v := range readVectors(t) {
		t.Run(v.Name, func(t *testing.T) {
			w, err := NewWebhook(v.Secrets...)
			if v.Expect.Refused {
				if err == nil {
					t.Fatal("the secret was accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			w = w.WithClock(func() time.Time { return time.Unix(v.Now, 0) })
			h := http.Header{}
			for name, value := range v.Headers {
				h[name] = []string{value}
			}
			checkVerify(t, v, w, h)
			checkVerifySignature(t, v, w, h)
		})
	}
}

func checkVerify(t *testing.T, v vector, w *Webhook, h http.Header) {
	t.Helper()
	ev, err := w.Verify([]byte(v.Body), h)
	if v.Expect.OK == nil {
		if reason := failureOf(err); reason != v.Expect.Error {
			t.Fatalf("Verify: got %v, want %s", err, v.Expect.Error)
		}
		return
	}
	if err != nil {
		t.Fatalf("Verify: %v", err)
	}
	if meta := ev.Meta(); meta.ID != v.Expect.OK.ID || meta.Type != v.Expect.OK.Type {
		t.Fatalf("Verify: got %s %s", meta.ID, meta.Type)
	}
	if _, unknown := ev.(UnknownEvent); unknown != v.Expect.OK.Unknown {
		t.Fatalf("Verify: UnknownEvent is %t, want %t", unknown, v.Expect.OK.Unknown)
	}
}

func checkVerifySignature(t *testing.T, v vector, w *Webhook, h http.Header) {
	t.Helper()
	err := w.VerifySignature([]byte(v.Body), h)
	if v.Expect.OK != nil || v.Expect.Error == MalformedPayload {
		if err != nil {
			t.Fatalf("VerifySignature: %v", err)
		}
		return
	}
	if reason := failureOf(err); reason != v.Expect.Error {
		t.Fatalf("VerifySignature: got %v, want %s", err, v.Expect.Error)
	}
}

func failureOf(err error) WebhookFailure {
	var failure *WebhookVerificationError
	if errors.As(err, &failure) {
		return failure.Reason
	}
	return ""
}

// TestTheVerifierIsOutsideTheErrorFamilyAndRedacted: ADR 30.9.26aa D4. A
// verification failure is not an Error, and no rendering of a Webhook shows
// its secret.
func TestTheVerifierIsOutsideTheErrorFamilyAndRedacted(t *testing.T) {
	var family Error
	if errors.As(error(&WebhookVerificationError{Reason: MissingHeader}), &family) {
		t.Fatal("WebhookVerificationError implements Error")
	}
	secret := "lgr_whsec_Y29uZm9ybWFuY2Utd2ViaG9vay1zZWNyZXQtMDAwMSE="
	w, err := NewWebhook(secret)
	if err != nil {
		t.Fatal(err)
	}
	for _, verb := range []string{"%v", "%+v", "%#v", "%s"} {
		if text := fmt.Sprintf(verb, w); regexp.MustCompile(`Y29uZm9y|conformance`).MatchString(text) {
			t.Fatalf("%s renders the secret: %s", verb, text)
		}
	}
}

// TestACancelDuringAReconnectSleepEndsTheTail: ADR 30.9.26aa D7 (CONTRACT.md
// K5a, Cancellation). A tail whose connection ends early sleeps before it
// reopens; cancelling the context in that sleep ends the tail with
// ctx.Err(), with no further request, and the cursor stays the last id.
func TestACancelDuringAReconnectSleepEndsTheTail(t *testing.T) {
	var requests atomic.Int32
	srv := newServer(t, &tokenEndpoint{}, func(w http.ResponseWriter, _ *http.Request) {
		requests.Add(1)
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "id: c1\nevent: event\ndata: "+tailEnvelope+"\n\n")
	})
	ctx, cancel := context.WithCancel(context.Background())
	c := newTestClient(t, srv, WithSleeper(func(ctx context.Context, _ time.Duration) error {
		cancel()
		return ctx.Err()
	}))
	tail := c.TailEvents(ctx, EventsOptions{})
	defer tail.Close()
	if ev, err := tail.Next(ctx); err != nil || ev.Meta().ID != "lgr_evt_unit1" {
		t.Fatalf("first Next: %v, %v", ev, err)
	}
	if _, err := tail.Next(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("second Next: %v, want context.Canceled", err)
	}
	if n := requests.Load(); n != 1 || tail.Cursor() != "c1" {
		t.Fatalf("%d requests, cursor %q; want 1 and c1", n, tail.Cursor())
	}
}

const tailEnvelope = `{"id":"lgr_evt_unit1","type":"lesson_plan.failed","created_at":"2026-10-01T09:13:02Z",` +
	`"api_version":"2026-09-equipped-boxfish","subject":"lgr_sub_unit","data":{"plan_id":"p1","reason":"timed_out"}}`

// TestGeneratedIdempotencyKeysAreUUIDv4: ADR 30.9.26aa D8.
func TestGeneratedIdempotencyKeysAreUUIDv4(t *testing.T) {
	pattern := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	seen := map[string]bool{}
	for range 100 {
		key, err := newUUIDv4()
		if err != nil {
			t.Fatal(err)
		}
		if !pattern.MatchString(key) || seen[key] {
			t.Fatalf("key %q", key)
		}
		seen[key] = true
	}
}
