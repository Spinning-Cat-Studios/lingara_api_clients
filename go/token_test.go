package lingara

import (
	"context"
	"errors"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// TestSingleFlightSharesOneExchangeAndCachesNoFailure: 29.9.26q AC6. Eight
// concurrent Token calls cause one exchange; when it fails, all eight get
// the same error and nothing is cached, so the next call exchanges again.
func TestSingleFlightSharesOneExchangeAndCachesNoFailure(t *testing.T) {
	var calls atomic.Int32
	release := make(chan struct{})
	srv := newServer(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		<-release
		jsonAnswer(w, 500, `{"error":"server_error"}`)
	}), nil)
	cc := credentials(t, newTestClient(t, srv))

	errs := make([]error, 8)
	var wg sync.WaitGroup
	for i := range errs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, errs[i] = cc.Token(context.Background())
		}()
	}
	time.Sleep(100 * time.Millisecond) // every caller is waiting on the flight
	close(release)
	wg.Wait()

	var oauth *OAuthError
	if !errors.As(errs[0], &oauth) || oauth.ErrorCode != "server_error" {
		t.Fatalf("got %v, want the OAuthError server_error", errs[0])
	}
	for i, err := range errs {
		if err != errs[0] {
			t.Errorf("waiter %d got %v, not the flight's own error", i, err)
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("%d exchanges for eight callers, want 1", calls.Load())
	}
	if _, err := cc.Token(context.Background()); err == nil || calls.Load() != 2 {
		t.Fatalf("after a failure: err %v and %d exchanges, want a fresh failing exchange", err, calls.Load())
	}
}

// TestRefreshesAtMinOfSixtySecondsAndHalfLifetime: 29.9.26q AC7. With
// expires_in 3600 a token is reused at 3539 s and replaced at 3541 s after
// it was sent; with expires_in 40 it is stale at 20 s.
func TestRefreshesAtMinOfSixtySecondsAndHalfLifetime(t *testing.T) {
	for _, c := range []struct {
		expiresIn        int
		reuseAt, freshAt time.Duration
	}{
		{3600, 3539 * time.Second, 3541 * time.Second},
		{40, 19 * time.Second, 20 * time.Second},
	} {
		endpoint := &tokenEndpoint{expiresIn: c.expiresIn}
		clock := newFakeClock()
		cc := credentials(t, newTestClient(t, newServer(t, endpoint, nil), WithClock(clock.Now)))
		ctx := context.Background()
		if _, err := cc.Token(ctx); err != nil {
			t.Fatal(err)
		}
		clock.Advance(c.reuseAt)
		if tok, _ := cc.Token(ctx); tok.raw != "lgr_at_1" {
			t.Errorf("expires_in %d: at %v got %s, want the cached token", c.expiresIn, c.reuseAt, tok.raw)
		}
		clock.Advance(c.freshAt - c.reuseAt)
		if tok, _ := cc.Token(ctx); tok.raw != "lgr_at_2" {
			t.Errorf("expires_in %d: at %v got %s, want a fresh token", c.expiresIn, c.freshAt, tok.raw)
		}
	}
}

// TestInvalidateIsCompareAndClear: 29.9.26q AC8. Invalidating an older
// token leaves a newer cached token in place.
func TestInvalidateIsCompareAndClear(t *testing.T) {
	endpoint := &tokenEndpoint{}
	cc := credentials(t, newTestClient(t, newServer(t, endpoint, nil)))
	ctx := context.Background()
	older, _ := cc.Token(ctx)
	cc.Invalidate(older)
	newer, _ := cc.Token(ctx)
	if newer.raw != "lgr_at_2" {
		t.Fatalf("after invalidating the cached token got %s, want a fresh one", newer.raw)
	}
	cc.Invalidate(older)
	if tok, _ := cc.Token(ctx); tok.raw != "lgr_at_2" || endpoint.calls.Load() != 2 {
		t.Fatalf("a stale Invalidate cleared the newer token: got %s after %d exchanges", tok.raw, endpoint.calls.Load())
	}
}

// TestCancelledWaiterLeavesFlightRunning: 29.9.26q AC9. A waiter cancelled
// during an exchange, the caller that started the flight included, returns
// context.Canceled at once, while the flight completes and its token is
// cached for the next caller.
func TestCancelledWaiterLeavesFlightRunning(t *testing.T) {
	endpoint := &tokenEndpoint{hold: make(chan struct{})}
	cc := credentials(t, newTestClient(t, newServer(t, endpoint, nil)))

	errs := make(chan error, 2)
	var cancels []context.CancelFunc
	for range 2 {
		ctx, cancel := context.WithCancel(context.Background())
		cancels = append(cancels, cancel)
		go func() { _, err := cc.Token(ctx); errs <- err }()
		time.Sleep(20 * time.Millisecond) // the first caller starts the flight
	}
	for _, cancel := range cancels {
		cancel()
	}
	for range 2 {
		select {
		case err := <-errs:
			if !errors.Is(err, context.Canceled) {
				t.Fatalf("a cancelled waiter got %v, want context.Canceled", err)
			}
		case <-time.After(time.Second):
			t.Fatal("a cancelled waiter did not return")
		}
	}
	close(endpoint.hold)
	tok, err := cc.Token(context.Background())
	if err != nil || tok.raw != "lgr_at_1" || endpoint.calls.Load() != 1 {
		t.Fatalf("got %s, %v after %d exchanges; want the abandoned flight's token", tok.raw, err, endpoint.calls.Load())
	}
}

// TestTokenRequestTimeoutBoundsEachAttempt: 29.9.26q AC26. A token endpoint
// that stalls past WithTokenRequestTimeout fails that attempt with Kind
// Timeout, every waiter on the flight gets the error, and the next Token
// call starts a fresh exchange.
func TestTokenRequestTimeoutBoundsEachAttempt(t *testing.T) {
	endpoint := &tokenEndpoint{hold: make(chan struct{})}
	cc := credentials(t, newTestClient(t, newServer(t, endpoint, nil), WithTokenRequestTimeout(100*time.Millisecond)))

	errs := make([]error, 3)
	var wg sync.WaitGroup
	for i := range errs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, errs[i] = cc.Token(context.Background())
		}()
	}
	wg.Wait()
	for i, err := range errs {
		var te *TransportError
		if !errors.As(err, &te) || te.Kind != Timeout {
			t.Fatalf("waiter %d got %v, want a Timeout TransportError", i, err)
		}
	}
	close(endpoint.hold)
	if tok, err := cc.Token(context.Background()); err != nil || tok.raw != "lgr_at_2" {
		t.Fatalf("got %s, %v; want a fresh exchange's token", tok.raw, err)
	}
}
