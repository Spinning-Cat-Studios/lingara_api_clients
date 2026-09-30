package lingara

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"iter"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Spinning-Cat-Studios/lingara_api_clients/go/internal/sse"
)

// K5: one stream of events (CONTRACT.md K5; ADR 29.9.26q D4, D4a).
//
// Frames come from the pure parser in internal/sse; this file owns the bytes,
// the idle timeout, the terminal events and closing the body on every exit
// path. The request is sent eagerly by the operation, so ranging never
// re-sends it: Events is single-use.

// ErrStreamConsumed is the one pair a second range over Events yields.
var ErrStreamConsumed = errors.New("lingara: this stream's events were already ranged over")

// armedTimers counts idle timers armed and not yet stopped, so a test can
// see that a finished stream leaves none behind.
var armedTimers atomic.Int64

// The request context's two private cancel causes: the idle timer's, and
// Close's. context.Cause tells either from the caller's own cancel.
var (
	errIdle   = errors.New("lingara: no byte arrived within the stream idle timeout")
	errClosed = errors.New("lingara: the stream was closed")
)

// streamRoute is a stream operation's body, event names and terminal table,
// generated from x-lingara-streams into routes_gen.go.
type streamRoute struct {
	requestBody string
	events      []string
	ends        map[string]ending
}

// ending is what an endsOn event does to iteration (CONTRACT.md K5's rule),
// generated from the view's endsOn (ADR 29.9.26ai).
type ending int

const (
	// endYield: yielded, then the stream ends.
	endYield ending = iota
	// endQuiet: a Done payload; the stream ends unyielded.
	endQuiet
	// endRaise: the failure event, returned as an *APIError with Status 200.
	endRaise
)

// Stream is one open stream of events of type E, the operation's generated
// union. Range over Events once; defer Close.
type Stream[E any] struct {
	route         *streamRoute
	decode        func(name string, frame []byte) (E, bool, error)
	body          io.ReadCloser
	ctx           context.Context // the caller's
	reqCtx        context.Context // the request's, cancelled with a cause
	cancel        context.CancelCauseFunc
	idle          time.Duration
	servedVersion string

	mu        sync.Mutex
	ranged    bool
	reading   bool
	closeOnce sync.Once
}

// ServedVersion is the Lingara-Version the server answered under, or "".
func (s *Stream[E]) ServedVersion() string { return s.servedVersion }

// Close ends the stream and closes the connection. It is idempotent and safe
// to call from another goroutine while a range is running; the loop then
// ends with no further pair.
func (s *Stream[E]) Close() error {
	s.cancel(errClosed)
	s.mu.Lock()
	defer s.mu.Unlock()
	// A running range closes the body itself on its way out, so a Read and
	// a Close never race on it.
	if !s.reading {
		s.closeBody()
	}
	return nil
}

func (s *Stream[E]) closeBody() { s.closeOnce.Do(func() { _ = s.body.Close() }) }

// Events yields each event. The last pair is (zero, err) when the stream
// fails: a K3 error, or ctx.Err() after a cancel. An error event is never
// yielded as an E: it is the last pair's *APIError. A break closes the body.
func (s *Stream[E]) Events() iter.Seq2[E, error] {
	return func(yield func(E, error) bool) {
		var zero E
		if !s.begin() {
			yield(zero, ErrStreamConsumed)
			return
		}
		r := &streamReader[E]{s: s, buf: make([]byte, 32<<10)}
		defer s.finish(r)
		for {
			st := r.next()
			switch st.out {
			case gotEnd:
				return
			case gotError:
				yield(zero, st.err)
				return
			}
			if !yield(st.ev, nil) || st.out == gotLast {
				return
			}
		}
	}
}

func (s *Stream[E]) begin() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ranged {
		return false
	}
	s.ranged, s.reading = true, true
	return true
}

// finish runs on every exit path: terminal, error, cancel, break and Close.
// A finished stream holds no goroutine, timer or connection.
func (s *Stream[E]) finish(r *streamReader[E]) {
	r.stopTimer()
	s.mu.Lock()
	s.reading = false
	s.closeBody()
	s.mu.Unlock()
	s.cancel(errClosed)
}

// outcome is what one frame, or one read, produced.
type outcome int

const (
	gotEvent outcome = iota // yield it and go on
	gotLast                 // yield it, then end
	gotEnd                  // end with no further pair
	gotError                // yield the error, then end
	gotSkip                 // an unknown event name: read on
)

// step is one outcome and what it carries.
type step[E any] struct {
	ev  E
	err error
	out outcome
}

type streamReader[E any] struct {
	s      *Stream[E]
	parser sse.Parser
	frames []sse.Frame
	eof    bool
	buf    []byte
	// Armed only while a read is pending: time the loop body spends on an
	// event, and time before the first range, never count.
	timer *time.Timer
}

func (r *streamReader[E]) next() step[E] {
	for {
		if err := r.s.ctx.Err(); err != nil {
			return step[E]{err: err, out: gotError}
		}
		if context.Cause(r.s.reqCtx) == errClosed {
			return step[E]{out: gotEnd}
		}
		if len(r.frames) > 0 {
			frame := r.frames[0]
			r.frames = r.frames[1:]
			if st := r.s.interpret(frame); st.out != gotSkip {
				return st
			}
			continue
		}
		if r.eof {
			return step[E]{err: &TransportError{Kind: StreamEndedEarly}, out: gotError}
		}
		if err := r.read(); errors.Is(err, errClosed) {
			return step[E]{out: gotEnd}
		} else if err != nil {
			return step[E]{err: err, out: gotError}
		}
	}
}

// read reads once into the parser. The idle timer is armed before a read
// with no buffered frame and stopped by any byte, a keepalive included.
func (r *streamReader[E]) read() error {
	s := r.s
	if r.timer == nil {
		armedTimers.Add(1)
		r.timer = time.AfterFunc(s.idle, func() { s.cancel(errIdle) })
	}
	n, err := s.body.Read(r.buf)
	if n > 0 {
		r.stopTimer()
		r.frames = append(r.frames, r.parser.Feed(r.buf[:n])...)
	}
	switch {
	case err == nil:
		return nil
	case errors.Is(err, io.EOF):
		r.stopTimer()
		r.parser.End()
		r.eof = true
		return nil
	}
	r.stopTimer()
	if s.ctx.Err() != nil {
		return s.ctx.Err()
	}
	switch context.Cause(s.reqCtx) {
	case errClosed:
		return errClosed
	case errIdle:
		return &TransportError{Kind: Timeout, Err: errIdle}
	}
	return transportError(s.ctx, err, true)
}

func (r *streamReader[E]) stopTimer() {
	if r.timer != nil {
		r.timer.Stop()
		r.timer = nil
		armedTimers.Add(-1)
	}
}

// interpret decides what one frame means: skip an unknown name, fail a known one
// whose data does not decode, and apply the terminal table.
func (s *Stream[E]) interpret(frame sse.Frame) step[E] {
	if !slices.Contains(s.route.events, frame.Event) {
		return step[E]{out: gotSkip}
	}
	data := []byte(frame.Data)
	if !json.Valid(data) {
		return step[E]{err: &TransportError{Kind: MalformedEvent}, out: gotError}
	}
	end, ends := s.route.ends[frame.Event]
	switch {
	case ends && end == endRaise:
		return step[E]{err: streamError(data, s.servedVersion), out: gotError}
	case ends && end == endQuiet:
		return step[E]{out: gotEnd}
	}
	wrapped, _ := json.Marshal(struct {
		Event string          `json:"event"`
		Data  json.RawMessage `json:"data"`
	}{frame.Event, data})
	ev, known, err := s.decode(frame.Event, wrapped)
	switch {
	case !known:
		return step[E]{out: gotSkip}
	case err != nil:
		return step[E]{err: &TransportError{Kind: MalformedEvent, Err: err}, out: gotError}
	case ends:
		return step[E]{ev: ev, out: gotLast}
	}
	return step[E]{ev: ev, out: gotEvent}
}

// streamError is an error event, returned as an *APIError with Status 200
// and never yielded or retried.
func streamError(data []byte, servedVersion string) *APIError {
	var fields map[string]any
	_ = json.Unmarshal(data, &fields)
	text := func(key, fallback string) string {
		if s, ok := fields[key].(string); ok {
			return s
		}
		return fallback
	}
	return &APIError{
		Status:        200,
		Code:          text("code", "stream_error"),
		Message:       text("message", "the stream reported an error"),
		PlanID:        text("plan_id", ""),
		ServedVersion: servedVersion,
	}
}
