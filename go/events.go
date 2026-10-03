package lingara

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"
)

// Events (CONTRACT.md, The event helpers; ADR 30.9.26aa D3). events_gen.go
// writes the union, its arms and ParseEvent from the view's x-lingara-events;
// this file holds what they share: the envelope fields, the envelope reader,
// and InboundEvent's wire form.

// ErrNoMoreEvents is what Next returns once a feed has reached the end of
// what the server holds, or once a tail is closed.
var ErrNoMoreEvents = errors.New("lingara: no more events")

// EventMeta is the envelope every event carries besides its data. ID is the
// event's deduplication key: delivery is at least once.
type EventMeta struct {
	ID         string    `json:"id"`
	Type       string    `json:"type"`
	CreatedAt  time.Time `json:"created_at"`
	APIVersion string    `json:"api_version"`
	Subject    string    `json:"subject"`
}

// Meta returns the envelope fields, so code that handles every arm can read
// them through Event.
func (m EventMeta) Meta() EventMeta { return m }

// readEnvelope reads the five envelope fields and the raw data. Every field
// is required, and data must be a JSON object.
func readEnvelope(raw []byte) (EventMeta, json.RawMessage, error) {
	var env struct {
		ID         *string         `json:"id"`
		Type       *string         `json:"type"`
		CreatedAt  *time.Time      `json:"created_at"`
		APIVersion *string         `json:"api_version"`
		Subject    *string         `json:"subject"`
		Data       json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		return EventMeta{}, nil, fmt.Errorf("lingara: not an event envelope: %w", err)
	}
	data := bytes.TrimSpace(env.Data)
	if env.ID == nil || env.Type == nil || env.CreatedAt == nil || env.APIVersion == nil || env.Subject == nil ||
		len(data) == 0 || data[0] != '{' {
		return EventMeta{}, nil, errors.New("lingara: not an event envelope: a field is missing")
	}
	meta := EventMeta{ID: *env.ID, Type: *env.Type, CreatedAt: *env.CreatedAt, APIVersion: *env.APIVersion, Subject: *env.Subject}
	return meta, data, nil
}

// decodeEventData decodes a known type's data into its model. encoding/json
// does not enforce required fields, but a value of the wrong JSON type fails
// here, so the data the catalogue promises is the data the arm holds.
func decodeEventData(eventType string, data json.RawMessage, into any) error {
	if err := json.Unmarshal(data, into); err != nil {
		return fmt.Errorf("lingara: %s: data does not decode: %w", eventType, err)
	}
	return nil
}

// Type is the wire type the event is sent as, or "" for the zero value.
func (e InboundEvent) Type() string { return e.eventType }

// MarshalJSON writes the {type, data} body POST /v1/events takes.
func (e InboundEvent) MarshalJSON() ([]byte, error) {
	if e.eventType == "" {
		return nil, errors.New("lingara: an InboundEvent must be built with one of its constructors")
	}
	return json.Marshal(struct {
		Type string `json:"type"`
		Data any    `json:"data"`
	}{e.eventType, e.data})
}

// lifetime is an iterator's own context. Next binds each call's context to
// it, so cancelling either ends the iterator, as the contract's cancellation
// rule asks; a context cancelled after Next returns changes nothing.
type lifetime struct {
	ctx    context.Context
	cancel context.CancelFunc
}

func newLifetime(ctx context.Context) lifetime {
	life, cancel := context.WithCancel(ctx)
	return lifetime{life, cancel}
}

// bind ends the lifetime when call is done, until the returned stop.
func (l lifetime) bind(call context.Context) func() bool { return context.AfterFunc(call, l.cancel) }

// cause is the caller's own context error when either context ended, and err
// otherwise.
func (l lifetime) cause(call context.Context, err error) error {
	if call.Err() != nil {
		return call.Err()
	}
	if l.ctx.Err() != nil {
		return context.Cause(l.ctx)
	}
	return err
}
