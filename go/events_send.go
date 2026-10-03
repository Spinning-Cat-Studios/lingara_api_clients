package lingara

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"net/http"
)

// SendEvent (CONTRACT.md K4 and The event helpers; ADR 30.9.26aa D8).

// SendEventOption configures one SendEvent call.
type SendEventOption func(*sendEventOptions)

type sendEventOptions struct{ idempotencyKey string }

// WithIdempotencyKey sends key as the Idempotency-Key, unchanged and not
// checked here: the server's 400 idempotency_key_required answers a bad one.
// Supply your own when you may resend after a crash, since a generated key
// does not outlive the call. A key reused for another event gets the first
// event's answer, not an error.
func WithIdempotencyKey(key string) SendEventOption {
	return func(o *sendEventOptions) { o.idempotencyKey = key }
}

// SendEvent tells Lingara what happened in your world (scope events:write,
// and lesson_plans:write when the event asks for generation). Without
// WithIdempotencyKey it generates a UUIDv4 once, before the first attempt,
// and every K4 retry sends the same key, so a retry gets the first answer.
// Only a reaction whose PlanStatus is generating promises a lesson_plan.*
// event.
func (c *Client) SendEvent(ctx context.Context, event InboundEvent, opts ...SendEventOption) (*Result[InboundEventAccepted], error) {
	var o sendEventOptions
	for _, opt := range opts {
		opt(&o)
	}
	body, err := json.Marshal(event)
	if err != nil {
		return nil, err
	}
	if o.idempotencyKey == "" {
		if o.idempotencyKey, err = newUUIDv4(); err != nil {
			return nil, err
		}
	}
	rt := routes["sendEvent"]
	return sendJSON[InboundEventAccepted](ctx, c, request{
		method: rt.method, url: c.url(rt.path, ""), body: body, accept: "application/json", needsToken: rt.needsToken,
		header: http.Header{"Idempotency-Key": {o.idempotencyKey}},
	})
}

// newUUIDv4 is a random UUID (RFC 9562 version 4) from crypto/rand.
func newUUIDv4() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", fmt.Errorf("lingara: generating an Idempotency-Key: %w", err)
	}
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16]), nil
}
