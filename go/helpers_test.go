package lingara

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Shared by the unit tests: an in-process server, a virtual clock and a
// recording sleeper. Nothing here reaches a live endpoint.

const (
	testClientID = "lgr_cid_unit0000000000000000"
	testSecret   = "lgr_cs_unit000000000000000000000000000000000000"
)

// fakeClock is a virtual clock that moves only when a test says so.
type fakeClock struct {
	mu  sync.Mutex
	now time.Time
}

func newFakeClock() *fakeClock { return &fakeClock{now: time.Unix(1_790_000_000, 0)} }

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

// sleepRecorder records each requested sleep and returns at once.
type sleepRecorder struct {
	mu     sync.Mutex
	sleeps []time.Duration
}

func (s *sleepRecorder) Sleep(_ context.Context, d time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sleeps = append(s.sleeps, d)
	return nil
}

func (s *sleepRecorder) Recorded() []time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]time.Duration(nil), s.sleeps...)
}

// tokenEndpoint answers the client-credentials grant with numbered tokens,
// lgr_at_1, lgr_at_2 and so on, and counts the exchanges.
type tokenEndpoint struct {
	calls     atomic.Int32
	expiresIn int
	// hold, when set, is waited on before each answer.
	hold chan struct{}
}

func (e *tokenEndpoint) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	n := e.calls.Add(1)
	if e.hold != nil {
		select {
		case <-e.hold:
		case <-r.Context().Done():
			return
		}
	}
	expires := e.expiresIn
	if expires == 0 {
		expires = 3600
	}
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprintf(w, `{"access_token":"lgr_at_%d","token_type":"Bearer","expires_in":%d}`, n, expires)
}

// newServer serves the token endpoint at /oauth/token and v1 at every other
// path, and closes when the test ends.
func newServer(t *testing.T, tokens http.Handler, v1 http.HandlerFunc) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	if tokens != nil {
		mux.Handle("/oauth/token", tokens)
	}
	if v1 != nil {
		mux.HandleFunc("/", v1)
	}
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

// newTestClient points a client with test credentials at srv.
func newTestClient(t *testing.T, srv *httptest.Server, opts ...Option) *Client {
	t.Helper()
	base := []Option{WithBaseURL(srv.URL), WithTokenURL(srv.URL + "/oauth/token"), WithClientCredentials(testClientID, testSecret)}
	c, err := New(append(base, opts...)...)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

// credentials is the client's own ClientCredentials.
func credentials(t *testing.T, c *Client) *ClientCredentials {
	t.Helper()
	cc, ok := c.tokens.(*ClientCredentials)
	if !ok {
		t.Fatal("the client has no ClientCredentials")
	}
	return cc
}

func jsonAnswer(w http.ResponseWriter, status int, body string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	fmt.Fprint(w, body)
}
