package lingara

import (
	"context"
	"encoding/json"
	"net/url"
	"strconv"
	"strings"
)

// The feed (CONTRACT.md, The event helpers; ADR 30.9.26aa D6): ListEvents is
// the operation, one page; Events walks the pages and parses each item.

// ListEventsOptions is ListEvents' query. A zero field is not sent.
type ListEventsOptions struct {
	// Cursor continues from an earlier page's NextCursor, or a tail's Cursor.
	Cursor string
	// Start is where to begin without a cursor: EventStartLatest (the
	// server's default) or EventStartOldest. Sending it with Cursor is a 400.
	Start EventStart
	// Types keeps only these event types.
	Types []string
	// Limit is the most events one page holds, 1 to 100.
	Limit int
}

// EventsOptions is where Events and TailEvents begin. Start is sent only when
// Cursor is empty.
type EventsOptions struct {
	Cursor string
	Start  EventStart
	Types  []string
}

// ListEvents fetches one page of events (scope events:read). Its items are
// the generated EventEnvelope, with Data a plain map; Events parses them.
func (c *Client) ListEvents(ctx context.Context, opts ListEventsOptions) (*Result[EventPage], error) {
	return sendJSON[EventPage](ctx, c, c.listEventsRequest(opts))
}

func (c *Client) listEventsRequest(opts ListEventsOptions) request {
	q := eventQuery(EventsOptions{Types: opts.Types})
	if opts.Cursor != "" {
		q.Set("cursor", opts.Cursor)
	}
	if opts.Start != "" {
		q.Set("start", string(opts.Start))
	}
	if opts.Limit != 0 {
		q.Set("limit", strconv.Itoa(opts.Limit))
	}
	rt := routes["listEvents"]
	return request{method: rt.method, url: withQuery(c.url(rt.path, ""), q), accept: "application/json", needsToken: rt.needsToken}
}

// eventQuery is the query both helpers share: types, comma-separated in one
// value, and start only without a cursor (the server's own precedence).
func eventQuery(opts EventsOptions) url.Values {
	q := url.Values{}
	if len(opts.Types) > 0 {
		q.Set("types", strings.Join(opts.Types, ","))
	}
	if opts.Start != "" && opts.Cursor == "" {
		q.Set("start", string(opts.Start))
	}
	return q
}

// EventFeed walks the feed from a cursor to the end of what the server holds.
// It never sleeps or polls: call Events again later with Cursor, or move to
// TailEvents. It is not safe for concurrent use.
type EventFeed struct {
	c      *Client
	life   lifetime
	opts   EventsOptions
	cursor string
	items  []json.RawMessage
	next   string
	last   bool
	err    error
}

// Events walks the feed (scope events:read): each Next returns one event, in
// order, and ErrNoMoreEvents after the page whose has_more is false. A known
// type whose data does not decode is a *TransportError of kind
// malformed_event; a refusal, 410 cursor_expired included, is its K3 error.
// Cancelling ctx, or the ctx of a running Next, ends the feed.
func (c *Client) Events(ctx context.Context, opts EventsOptions) *EventFeed {
	return &EventFeed{c: c, life: newLifetime(ctx), opts: opts, cursor: opts.Cursor}
}

// Cursor is where to resume: after a page's last event is returned, that
// page's next_cursor, which an empty page also advances.
func (f *EventFeed) Cursor() string { return f.cursor }

// Next returns the next event. After any error, every later call returns it
// again.
func (f *EventFeed) Next(ctx context.Context) (Event, error) {
	if f.err != nil {
		return nil, f.err
	}
	stop := f.life.bind(ctx)
	defer stop()
	ev, err := f.advance()
	if err != nil {
		f.err = f.life.cause(ctx, err)
		f.life.cancel()
	}
	return ev, f.err
}

func (f *EventFeed) advance() (Event, error) {
	for len(f.items) == 0 {
		if f.last {
			return nil, ErrNoMoreEvents
		}
		if err := f.fetch(); err != nil {
			return nil, err
		}
	}
	raw := f.items[0]
	f.items = f.items[1:]
	ev, err := ParseEvent(raw)
	if err != nil {
		return nil, &TransportError{Kind: MalformedEvent, Err: err}
	}
	if len(f.items) == 0 {
		f.cursor = f.next
	}
	return ev, nil
}

// fetch reads the page after the cursor. Its items stay raw, so ParseEvent
// reads the bytes the server sent.
func (f *EventFeed) fetch() error {
	opts := ListEventsOptions{Cursor: f.cursor, Types: f.opts.Types}
	if f.cursor == "" {
		opts.Start = f.opts.Start
	}
	res, err := sendJSON[struct {
		Items      []json.RawMessage `json:"items"`
		NextCursor *string           `json:"next_cursor"`
		HasMore    *bool             `json:"has_more"`
	}](f.life.ctx, f.c, f.c.listEventsRequest(opts))
	if err != nil {
		return err
	}
	page := res.Value
	if page.NextCursor == nil || page.HasMore == nil {
		return &TransportError{Kind: MalformedResponse}
	}
	f.items, f.next, f.last = page.Items, *page.NextCursor, !*page.HasMore
	if len(f.items) == 0 {
		f.cursor = f.next
	}
	return nil
}
