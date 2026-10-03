package lingara

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"time"
)

// The tail (CONTRACT.md K5a; ADR 30.9.26aa D7). StreamEvents is the
// operation: one connection under K5, which ends on done. TailEvents is the
// helper built on it: it reconnects after every ending with Last-Event-ID,
// backs off on failure, and never ends on its own.

// StreamEventsOptions is StreamEvents' query and Last-Event-ID header. A zero
// field is not sent.
type StreamEventsOptions struct {
	// LastEventID resumes after this cursor; the server then ignores Cursor
	// and Start.
	LastEventID string
	Cursor      string
	Start       EventStart
	Types       []string
}

// StreamEvents opens one connection to the event stream (scope events:read):
// a Stream whose event branch holds the generated EventEnvelope. done ends
// it unyielded and error is its last pair, as on every K5 stream.
func (c *Client) StreamEvents(ctx context.Context, opts StreamEventsOptions) (*Stream[StreamEventsEvent], error) {
	q := eventQuery(EventsOptions{Types: opts.Types})
	if opts.Cursor != "" {
		q.Set("cursor", opts.Cursor)
	}
	if opts.Start != "" {
		q.Set("start", string(opts.Start))
	}
	return openStream(ctx, c, streamCall{operationID: "streamEvents", query: q, header: lastEventID(opts.LastEventID)}, decodeStreamEventsEvent)
}

// lastEventID is the Last-Event-ID header for a known cursor, or none.
func lastEventID(cursor string) http.Header {
	if cursor == "" {
		return nil
	}
	return http.Header{"Last-Event-Id": {cursor}}
}

// EventTail is a reconnecting subscription to the event stream. Next returns
// each event; Close ends it. It is not safe for concurrent use: to stop a
// running Next from elsewhere, cancel its context.
type EventTail struct {
	c        *Client
	life     lifetime
	query    url.Values
	cursor   string
	failures int
	stream   *Stream[Event]
	reader   *streamReader[Event]
	err      error
}

// TailEvents follows the event stream from opts (scope events:read). Cursor
// is sent as Last-Event-ID, never in the query; without one, Start is. Every
// reconnection repeats the first query and resumes from Cursor. A done
// reconnects at once; an error event, an early end or a transport failure
// backs off 1 s, doubling to 30 s, and the WithTailMaxFailures-th failure in
// a row is returned. A 429 or 503 is one failure whose Retry-After, within
// WithRetryAfterCap, replaces that step's delay; any other refusal, and a
// known type whose data does not decode, is returned at once. Cancelling
// ctx, or the ctx of a running Next, ends the tail.
func (c *Client) TailEvents(ctx context.Context, opts EventsOptions) *EventTail {
	return &EventTail{c: c, life: newLifetime(ctx), query: eventQuery(opts), cursor: opts.Cursor}
}

// Cursor is the id of the last frame that carried one, an event's or a
// done's: where a new tail or feed resumes.
func (t *EventTail) Cursor() string { return t.cursor }

// Close ends the tail and its connection. Next then returns ErrNoMoreEvents.
func (t *EventTail) Close() error {
	t.drop()
	t.life.cancel()
	if t.err == nil {
		t.err = ErrNoMoreEvents
	}
	return nil
}

// Next returns the next event. After any error, every later call returns it
// again.
func (t *EventTail) Next(ctx context.Context) (Event, error) {
	if t.err != nil {
		return nil, t.err
	}
	stop := t.life.bind(ctx)
	defer stop()
	ev, err := t.next()
	if err != nil {
		t.drop()
		t.err = t.life.cause(ctx, err)
		t.life.cancel()
	}
	return ev, t.err
}

// next reads the open connection, opening one first when there is none.
func (t *EventTail) next() (Event, error) {
	for {
		if t.reader == nil {
			if err := t.open(); err != nil {
				return nil, err
			}
			continue
		}
		st := t.reader.next()
		if t.reader.lastID != "" {
			t.cursor = t.reader.lastID
		}
		switch st.out {
		case gotEvent, gotLast:
			t.failures = 0
			return st.ev, nil
		case gotEnd:
			// A done: its id is already the cursor, and the next open is
			// at once and not a failure.
			t.drop()
			t.failures = 0
		default:
			t.drop()
			if err := t.ended(st.err); err != nil {
				return nil, err
			}
		}
	}
}

// ended decides what a connection's failure does: a malformed event or a
// cancellation is returned, anything else is one failure.
func (t *EventTail) ended(err error) error {
	var transport *TransportError
	if t.life.ctx.Err() != nil || (errors.As(err, &transport) && transport.Kind == MalformedEvent) {
		return err
	}
	return t.fail(err, nil)
}

// open opens one connection, with no K4 attempt loop. A refusal is a failure
// only when it is a 429 or 503.
func (t *EventTail) open() error {
	s, err := openStream(t.life.ctx, t.c, streamCall{
		operationID: "streamEvents", query: t.query, header: lastEventID(t.cursor), single: true,
	}, decodeTailFrame)
	if err == nil {
		s.begin()
		t.stream, t.reader = s, &streamReader[Event]{s: s, buf: make([]byte, 32<<10)}
		return nil
	}
	if t.life.ctx.Err() != nil {
		return err
	}
	if wait, busy := busyRetryAfter(err); busy {
		if wait != nil && *wait > t.c.policy.retryAfterCap {
			return err
		}
		return t.fail(err, wait)
	}
	var transport *TransportError
	if errors.As(err, &transport) {
		return t.fail(err, nil)
	}
	return err
}

// busyRetryAfter says whether err is a 429 or 503, and its Retry-After.
func busyRetryAfter(err error) (*time.Duration, bool) {
	var api *APIError
	var maintenance *MaintenanceError
	switch {
	case errors.As(err, &api):
		busy := api.Status == http.StatusTooManyRequests || api.Status == http.StatusServiceUnavailable
		return api.RetryAfter, busy
	case errors.As(err, &maintenance):
		return maintenance.RetryAfter, true
	}
	return nil, false
}

// fail counts one failure: the bound returns err, and otherwise it sleeps the
// backoff step, or wait in its place.
func (t *EventTail) fail(err error, wait *time.Duration) error {
	t.failures++
	if t.failures >= t.c.tailMax {
		return err
	}
	delay := min(time.Second<<min(t.failures-1, 5), 30*time.Second)
	if wait != nil {
		delay = *wait
	}
	if sleepErr := t.c.policy.sleep(t.life.ctx, delay); sleepErr != nil {
		return sleepErr
	}
	return t.life.ctx.Err()
}

// drop finishes the open connection, if any.
func (t *EventTail) drop() {
	if t.reader != nil {
		t.stream.finish(t.reader)
		t.stream, t.reader = nil, nil
	}
}

// decodeTailFrame parses an event frame's data, the envelope, into an Event.
// done and error never reach it: the terminal table takes them first.
func decodeTailFrame(name string, frame []byte) (Event, bool, error) {
	if name != "event" {
		return nil, false, nil
	}
	var f struct {
		Data json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal(frame, &f); err != nil {
		return nil, true, err
	}
	ev, err := ParseEvent(f.Data)
	return ev, true, err
}
