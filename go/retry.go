package lingara

import (
	"context"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// The two retries (CONTRACT.md K1 and K4; ADR 29.9.26q D4): the Retry-After
// loop around one HTTP request, and the one 401 retry around a /v1 call.
//
// Each HTTP request has its own budget of maxAttempts. The token exchange is
// one request and the /v1 request another, and the 401 retry sends the /v1
// request again with a fresh budget.

// retryPolicy is K4's knobs and the two seams they are read and slept against.
type retryPolicy struct {
	// maxAttempts counts the first try; 1 turns retries off.
	maxAttempts int
	// retryAfterCap: a Retry-After above it is raised, never slept.
	retryAfterCap time.Duration
	clock         func() time.Time
	sleep         func(context.Context, time.Duration) error
}

// parseRetryAfter reads Retry-After as delta-seconds, or as an HTTP-date
// against now (max(0, date − now), rounded up to a whole second). Absent or
// unreadable is nil.
func parseRetryAfter(h http.Header, now time.Time) *time.Duration {
	value := strings.TrimSpace(h.Get("Retry-After"))
	if value == "" {
		return nil
	}
	if seconds, err := strconv.ParseUint(value, 10, 64); err == nil {
		// Clamped so a huge value cannot overflow a Duration: it is far
		// above any cap either way.
		d := time.Duration(min(seconds, 1<<32)) * time.Second
		return &d
	}
	at, err := http.ParseTime(value)
	if err != nil {
		return nil
	}
	wait := max(at.Sub(now), 0)
	whole := (wait + time.Second - 1).Truncate(time.Second)
	return &whole
}

// retryWait is how long to wait before trying again, or false to hand the
// response back. It is decided on the status line and headers alone.
func (p retryPolicy) retryWait(res *http.Response, tries int) (time.Duration, bool) {
	if res.StatusCode != http.StatusTooManyRequests && res.StatusCode != http.StatusServiceUnavailable {
		return 0, false
	}
	if tries >= p.maxAttempts {
		return 0, false
	}
	wait := parseRetryAfter(res.Header, p.clock())
	if wait == nil || *wait > p.retryAfterCap {
		return 0, false
	}
	return *wait, true
}

// withRetries sends attempt until it answers something other than a
// retryable 429 or 503, or the attempts run out, and returns the last
// response. A retried response is closed unread. A transport error is never
// retried, and a cancelled sleep ends the call with ctx.Err().
func withRetries(ctx context.Context, p retryPolicy, attempt func() (*http.Response, error)) (*http.Response, error) {
	for tries := 1; ; tries++ {
		res, err := attempt()
		if err != nil {
			return nil, err
		}
		wait, retry := p.retryWait(res, tries)
		if !retry {
			return res, nil
		}
		_, _ = io.Copy(io.Discard, io.LimitReader(res.Body, 64<<10))
		res.Body.Close()
		if err := p.sleep(ctx, wait); err != nil {
			return nil, err
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
	}
}

// withTokenRetry is K1's one 401 retry: send with a token; on a 401, forget
// that token (only if it is still the cached one), get another and send once
// more. A second 401 is handed back for the caller to map.
func withTokenRetry(ctx context.Context, tokens TokenSource, send func(Token) (*http.Response, error)) (*http.Response, error) {
	first, err := tokens.Token(ctx)
	if err != nil {
		return nil, err
	}
	res, err := send(first)
	if err != nil || res.StatusCode != http.StatusUnauthorized {
		return res, err
	}
	res.Body.Close()
	tokens.Invalidate(first)
	next, err := tokens.Token(ctx)
	if err != nil {
		return nil, err
	}
	return send(next)
}
