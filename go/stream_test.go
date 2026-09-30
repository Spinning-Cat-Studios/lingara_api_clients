package lingara

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"reflect"
	"slices"
	"sync/atomic"
	"testing"
	"time"
)

const (
	evStarted = "event: started\ndata: {\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\",\"framework\":\"HSK\",\"count\":1,\"ai_generated\":true}}\n\n"
	evItem    = "event: item\ndata: {\"word\":\"你好\",\"translation\":\"hello\"}\n\n"
	evDone    = "event: done\ndata: {}\n\n"
	keepalive = ": keepalive\n\n"
)

// sseScript is one stream response: chunks written and flushed one by one,
// each after its pause, then the body ends or, with hold, stays open until
// the client disconnects.
type sseScript struct {
	chunks       []string
	pauses       map[int]time.Duration // before chunk i
	hold         bool
	headers      map[string]string
	requests     atomic.Int32
	disconnected chan struct{}
}

func (s *sseScript) serve(w http.ResponseWriter, r *http.Request) {
	s.requests.Add(1)
	for k, v := range s.headers {
		w.Header().Set(k, v)
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.WriteHeader(200)
	for i, chunk := range s.chunks {
		select {
		case <-time.After(s.pauses[i]):
		case <-r.Context().Done():
			return
		}
		_, _ = w.Write([]byte(chunk))
		w.(http.Flusher).Flush()
	}
	if s.hold {
		<-r.Context().Done()
		close(s.disconnected)
	}
}

func streamClient(t *testing.T, script *sseScript, opts ...Option) *Client {
	t.Helper()
	script.disconnected = make(chan struct{})
	return newTestClient(t, newServer(t, &tokenEndpoint{}, script.serve), opts...)
}

func vocab(t *testing.T, ctx context.Context, c *Client) *Stream[GenerateVocabularyEvent] {
	t.Helper()
	s, err := c.GenerateVocabulary(ctx, VocabRequest{Level: 2, SourceLang: "en", TargetLang: "zh"})
	if err != nil {
		t.Fatal(err)
	}
	return s
}

// names is each yielded event's name, read from its JSON form.
func names[E any](events []E) []string {
	out := []string{}
	for _, ev := range events {
		var tagged struct{ Event string }
		raw, _ := json.Marshal(ev)
		_ = json.Unmarshal(raw, &tagged)
		out = append(out, tagged.Event)
	}
	return out
}

// drain ranges over a stream and returns what it yielded and its last error.
func drain[E any](s *Stream[E]) ([]E, error) {
	var events []E
	for ev, err := range s.Events() {
		if err != nil {
			return events, err
		}
		events = append(events, ev)
	}
	return events, nil
}

func waitDisconnect(t *testing.T, script *sseScript) {
	t.Helper()
	select {
	case <-script.disconnected:
	case <-time.After(2 * time.Second):
		t.Fatal("the server saw no disconnect within 2 s")
	}
}

// TestBreakClosesBodyAndStreamIsSingleUse: 29.9.26q AC15. Breaking out of
// range s.Events() closes the response body; a second range yields
// ErrStreamConsumed; and Close from another goroutine mid-range ends the
// loop with no further pair and no race under -race.
func TestBreakClosesBodyAndStreamIsSingleUse(t *testing.T) {
	script := &sseScript{chunks: []string{evStarted}, hold: true}
	s := vocab(t, context.Background(), streamClient(t, script))
	for range s.Events() {
		break
	}
	waitDisconnect(t, script)
	var pairs []error
	for _, err := range s.Events() {
		pairs = append(pairs, err)
	}
	if len(pairs) != 1 || !errors.Is(pairs[0], ErrStreamConsumed) {
		t.Fatalf("a second range yielded %v, want one ErrStreamConsumed", pairs)
	}

	script = &sseScript{chunks: []string{evStarted}, hold: true}
	s = vocab(t, context.Background(), streamClient(t, script))
	count := 0
	for _, err := range s.Events() {
		count++
		if err != nil {
			t.Fatalf("pair %d is an error after Close: %v", count, err)
		}
		go func() { _ = s.Close() }()
	}
	if count != 1 {
		t.Fatalf("%d pairs, want only the event before Close", count)
	}
	waitDisconnect(t, script)
	_ = s.Close() // idempotent
}

// TestContextCancelClosesTheStream: 29.9.26q AC16. Cancelling the context
// mid-stream closes the body and ends iteration with context.Canceled.
func TestContextCancelClosesTheStream(t *testing.T) {
	script := &sseScript{chunks: []string{evStarted}, hold: true}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	s := vocab(t, ctx, streamClient(t, script))
	var last error
	for ev, err := range s.Events() {
		if ev != nil {
			cancel()
		}
		last = err
	}
	if !errors.Is(last, context.Canceled) {
		t.Fatalf("the last pair was %v, want context.Canceled", last)
	}
	waitDisconnect(t, script)
}

// TestIdleTimeoutIsAnOptionAndKeepaliveResetsIt: 29.9.26q AC17. With a 500
// ms idle timeout, a stream silent for 1 s while a read is pending fails
// with Kind Timeout, a keepalive every 50 ms keeps it open, a loop body that
// holds one event for 1 s then continues gets the next event with no error,
// a caller's cancel still ends it with context.Canceled, and no timer is
// left running after the stream ends.
func TestIdleTimeoutIsAnOptionAndKeepaliveResetsIt(t *testing.T) {
	idle := WithStreamIdleTimeout(500 * time.Millisecond)

	silent := &sseScript{chunks: []string{evStarted}, hold: true}
	var te *TransportError
	if _, err := drain(vocab(t, context.Background(), streamClient(t, silent, idle))); !errors.As(err, &te) || te.Kind != Timeout {
		t.Errorf("a silent stream ended with %v, want Kind Timeout", err)
	}

	kept := &sseScript{chunks: []string{evStarted}, pauses: map[int]time.Duration{}}
	for i := 1; i <= 20; i++ {
		kept.chunks = append(kept.chunks, keepalive)
		kept.pauses[i] = 50 * time.Millisecond
	}
	kept.chunks = append(kept.chunks, evItem, evDone)
	if events, err := drain(vocab(t, context.Background(), streamClient(t, kept, idle))); err != nil || len(events) != 2 {
		t.Errorf("1 s of keepalives: %d events and %v, want 2 events and no error", len(events), err)
	}

	holdEachEvent(t, idle)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	time.AfterFunc(100*time.Millisecond, cancel)
	if _, err := drain(vocab(t, ctx, streamClient(t, &sseScript{chunks: []string{evStarted}, hold: true}, idle))); !errors.Is(err, context.Canceled) {
		t.Errorf("a caller's cancel ended the stream with %v", err)
	}
	if n := armedTimers.Load(); n != 0 {
		t.Errorf("%d idle timers are still armed after every stream ended", n)
	}
}

// holdEachEvent is AC17's slow consumer: the loop body holds each event for
// 1 s, twice the idle timeout, and the item arrives 400 ms in. The timer is
// not running while the body holds an event, so nothing times out.
func holdEachEvent(t *testing.T, idle Option) {
	t.Helper()
	slowConsumer := &sseScript{chunks: []string{evStarted, evItem, evDone}, pauses: map[int]time.Duration{1: 400 * time.Millisecond}}
	var got []string
	for ev, err := range vocab(t, context.Background(), streamClient(t, slowConsumer, idle)).Events() {
		if err != nil {
			t.Fatalf("after the loop body held an event for 1 s: %v", err)
		}
		got = append(got, names([]GenerateVocabularyEvent{ev})...)
		time.Sleep(time.Second)
	}
	if !reflect.DeepEqual(got, []string{"started", "item"}) {
		t.Errorf("a slow loop body saw %v", got)
	}
}

// TestEachOperationEndsOnItsOwnTerminal: 29.9.26q AC18. Each stream
// operation ends on its own C2 D6 terminal (result and pending yielded, done
// not); the terminal table and routes_gen.go name the same four operations,
// and every terminal is among its operation's event names.
func TestEachOperationEndsOnItsOwnTerminal(t *testing.T) {
	const after = "event: item\ndata: {\"word\":\"late\",\"translation\":\"late\"}\n\n"
	plan := `{"plan":{"id":"p1","status":"complete","source_lang":"en","target_lang":"zh","level":2,"ai_generated":true,"created_at":"2026-09-23T10:00:00Z"}}`
	started, phase := "event: started\ndata: {\"plan_id\":\"p1\"}\n\n", "event: phase\ndata: {\"phase\":\"drafting\",\"attempt\":1}\n\n"
	ctx := context.Background()
	check := func(name string, script *sseScript, open func(*Client) ([]string, error), want []string) {
		got, err := open(streamClient(t, script))
		if err != nil || !reflect.DeepEqual(got, want) {
			t.Errorf("%s: yielded %v and %v, want %v", name, got, err, want)
		}
	}
	check("generateVocabulary", &sseScript{chunks: []string{evStarted, evItem, evDone, after}}, func(c *Client) ([]string, error) {
		events, err := drain(vocab(t, ctx, c))
		return names(events), err
	}, []string{"started", "item"})
	check("createLessonPlan", &sseScript{chunks: []string{started, phase, "event: result\ndata: " + plan + "\n\n", after}}, func(c *Client) ([]string, error) {
		s, _ := c.CreateLessonPlan(ctx, LessonPlanCreateRequest{})
		events, err := drain(s)
		return names(events), err
	}, []string{"started", "phase", "result"})
	check("streamLessonPlan", &sseScript{chunks: []string{started, "event: pending\ndata: {\"plan_id\":\"p1\",\"status\":\"generating\"}\n\n", after}}, func(c *Client) ([]string, error) {
		s, _ := c.StreamLessonPlan(ctx, "p1")
		events, err := drain(s)
		return names(events), err
	}, []string{"started", "pending"})
	check("sendTutorMessage", &sseScript{chunks: []string{"event: delta\ndata: {\"text\":\"你\"}\n\n", "event: notice\ndata: {\"code\":\"c\",\"message\":\"m\"}\n\n", evDone, after}}, func(c *Client) ([]string, error) {
		s, _ := c.SendTutorMessage(ctx, TutorTurnRequest{})
		events, err := drain(s)
		return names(events), err
	}, []string{"delta", "notice"})

	streams := 0
	for id, r := range routes {
		if r.stream == nil {
			continue
		}
		streams++
		if len(r.stream.ends) == 0 {
			t.Errorf("%s has no terminal", id)
		}
		for event := range r.stream.ends {
			if !slices.Contains(r.stream.events, event) {
				t.Errorf("%s ends on %s, which is not among its events", id, event)
			}
		}
	}
	if streams != 4 {
		t.Errorf("%d stream routes, want 4", streams)
	}
}

// TestWrongShapedDataIsMalformedEvent: 29.9.26q AC19. A known event whose
// data is valid JSON of a mismatched type (an array where the branch
// expects an object) ends iteration with Kind MalformedEvent and is never
// yielded, and an unknown event name is skipped.
func TestWrongShapedDataIsMalformedEvent(t *testing.T) {
	script := &sseScript{chunks: []string{evStarted, "event: mystery\ndata: {\"x\":1}\n\n", "event: item\ndata: [1,2]\n\n", evDone}}
	events, err := drain(vocab(t, context.Background(), streamClient(t, script)))
	var te *TransportError
	if !errors.As(err, &te) || te.Kind != MalformedEvent {
		t.Fatalf("ended with %v, want Kind MalformedEvent", err)
	}
	if got := names(events); !reflect.DeepEqual(got, []string{"started"}) {
		t.Fatalf("yielded %v, want only started: the unknown event skipped, the malformed item never yielded", got)
	}
}

// TestErrorEventRaisesAPIErrorWithPlanID: 29.9.26q AC20. An error event ends
// iteration with an *APIError carrying Status 200, Code, Message, PlanID
// and ServedVersion; it is never yielded as an event and never retried.
func TestErrorEventRaisesAPIErrorWithPlanID(t *testing.T) {
	script := &sseScript{
		chunks:  []string{"event: started\ndata: {\"plan_id\":\"p1\"}\n\n", "event: error\ndata: {\"code\":\"generation_failed\",\"message\":\"The model gave up.\",\"plan_id\":\"p1\"}\n\n"},
		headers: map[string]string{"Lingara-Version": "2026-09-affable-cat"},
	}
	c := streamClient(t, script)
	s, err := c.CreateLessonPlan(context.Background(), LessonPlanCreateRequest{})
	if err != nil {
		t.Fatal(err)
	}
	events, err := drain(s)
	want := &APIError{Status: 200, Code: "generation_failed", Message: "The model gave up.", PlanID: "p1", ServedVersion: "2026-09-affable-cat"}
	var api *APIError
	if !errors.As(err, &api) || !reflect.DeepEqual(api, want) {
		t.Fatalf("ended with %#v, want %#v", err, want)
	}
	if got := names(events); !reflect.DeepEqual(got, []string{"started"}) || script.requests.Load() != 1 {
		t.Fatalf("yielded %v over %d requests; want only started, and no retry", got, script.requests.Load())
	}
}
