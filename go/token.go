package lingara

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"math"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// K1: the token source (CONTRACT.md K1; ADR 29.9.26q D4).
//
// ClientCredentials caches one token, refreshes it min(60 s, expires_in / 2)
// before it expires, shares one exchange between concurrent callers, and
// clears only the token a 401 was answered with.
//
// The exchange runs in its own goroutine under context.WithoutCancel, so a
// cancelled caller abandons only its own wait: the flight runs on and caches
// its token for the next caller. Detached from every caller it needs a bound
// of its own, so each HTTP attempt of it carries WithTokenRequestTimeout.

// TokenSource is where the client gets its access tokens. A caller may supply
// their own with WithTokenSource.
type TokenSource interface {
	// Token returns an access token, cached or freshly exchanged.
	Token(ctx context.Context) (Token, error)
	// Invalidate forgets tok only if it is still the cached token.
	Invalidate(tok Token)
}

// Token is an opaque access token. It renders as [REDACTED]; ExposeSecret is
// the one way to read it.
type Token struct{ raw string }

// NewToken wraps a raw access token, so a caller's own TokenSource can return
// one.
func NewToken(raw string) Token { return Token{raw} }

// ExposeSecret returns the raw token: the one accessor that does not redact.
func (t Token) ExposeSecret() string { return t.raw }

// ClientCredentials is the OAuth 2.0 client-credentials grant against the
// token URL: the TokenSource a client builds from WithClientCredentials.
type ClientCredentials struct {
	clientID string
	secret   ClientSecret
	cfg      exchangeConfig

	mu     sync.Mutex
	cached *cachedToken
	flight *flight
}

// exchangeConfig is everything the exchange needs besides the credentials.
type exchangeConfig struct {
	http       *http.Client
	tokenURL   string
	userAgent  string
	secretPost bool
	scopes     []string
	policy     retryPolicy
	timeout    time.Duration
}

type cachedToken struct {
	token   Token
	staleAt time.Time
}

// flight is the one exchange every concurrent caller waits on.
type flight struct {
	done  chan struct{}
	token Token
	err   error
}

func newClientCredentials(clientID string, secret ClientSecret, cfg exchangeConfig) *ClientCredentials {
	return &ClientCredentials{clientID: clientID, secret: secret, cfg: cfg}
}

// Token returns the cached token while it is fresh, and otherwise waits on
// the one flight, starting it if none is running. Every waiter, the one that
// started it included, can leave on ctx; the flight itself never does.
func (c *ClientCredentials) Token(ctx context.Context) (Token, error) {
	c.mu.Lock()
	if c.cached != nil && c.cfg.policy.clock().Before(c.cached.staleAt) {
		tok := c.cached.token
		c.mu.Unlock()
		return tok, nil
	}
	f := c.flight
	if f == nil {
		f = &flight{done: make(chan struct{})}
		c.flight = f
		go c.fly(context.WithoutCancel(ctx), f)
	}
	c.mu.Unlock()
	select {
	case <-f.done:
		return f.token, f.err
	case <-ctx.Done():
		return Token{}, ctx.Err()
	}
}

// Invalidate clears tok only if it is still the cached token. During an
// exchange it is a no-op: nothing is cached yet.
func (c *ClientCredentials) Invalidate(tok Token) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.cached != nil && c.cached.token.raw == tok.raw {
		c.cached = nil
	}
}

// fly runs the exchange and publishes its result: the token is cached on
// success, and nothing is on failure (there is no negative caching).
func (c *ClientCredentials) fly(ctx context.Context, f *flight) {
	cached, err := c.exchange(ctx)
	c.mu.Lock()
	c.flight = nil
	if err == nil {
		c.cached = cached
		f.token = cached.token
	}
	f.err = err
	c.mu.Unlock()
	close(f.done)
}

func (c *ClientCredentials) exchange(ctx context.Context) (*cachedToken, error) {
	policy := c.cfg.policy
	var sentAt time.Time
	res, err := withRetries(ctx, policy, func() (*http.Response, error) {
		// obtained_at is when the request that succeeded was sent.
		sentAt = policy.clock()
		return c.post(ctx)
	})
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(res.Body)
	if res.StatusCode < 200 || res.StatusCode > 299 {
		return nil, refusalError(endpointToken, res, body, policy.clock())
	}
	tok, lifetime, ok := grant(body)
	if !ok {
		return nil, &TransportError{Kind: MalformedResponse}
	}
	skew := min(60*time.Second, lifetime/2)
	return &cachedToken{token: tok, staleAt: sentAt.Add(lifetime - skew)}, nil
}

// post sends one exchange attempt and reads its whole body, both under the
// token request timeout, and hands back the response with the body in
// memory. Retry-After sleeps happen between attempts, so they are never
// counted against it.
func (c *ClientCredentials) post(ctx context.Context) (*http.Response, error) {
	attemptCtx, cancel := context.WithTimeout(ctx, c.cfg.timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(attemptCtx, http.MethodPost, c.cfg.tokenURL, strings.NewReader(c.form()))
	if err != nil {
		return nil, &TransportError{Kind: Connect, Err: err}
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", c.cfg.userAgent)
	if !c.cfg.secretPost {
		req.Header.Set("Authorization", basicAuth(c.clientID, c.secret.raw))
	}
	res, err := c.cfg.http.Do(req)
	if err != nil {
		return nil, transportError(ctx, err, false, c.secret.raw)
	}
	defer res.Body.Close()
	body, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, transportError(ctx, err, true, c.secret.raw)
	}
	res.Body = io.NopCloser(bytes.NewReader(body))
	return res, nil
}

// form is the exchange body: the grant, any scopes, and the credentials only
// under client_secret_post, never both places.
func (c *ClientCredentials) form() string {
	form := url.Values{"grant_type": {"client_credentials"}}
	if len(c.cfg.scopes) > 0 {
		form.Set("scope", strings.Join(c.cfg.scopes, " "))
	}
	if c.cfg.secretPost {
		form.Set("client_id", c.clientID)
		form.Set("client_secret", c.secret.raw)
	}
	return form.Encode()
}

// basicAuth is `Basic base64(form(id) ":" form(secret))`, each half
// form-encoded per RFC 6749 §2.3.1. req.SetBasicAuth does not form-encode, so
// it is never used.
func basicAuth(id, secret string) string {
	pair := url.QueryEscape(id) + ":" + url.QueryEscape(secret)
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(pair))
}

// grant reads a 200's token and lifetime. It is malformed unless it has an
// access_token, an expires_in and a Bearer token_type.
func grant(body []byte) (Token, time.Duration, bool) {
	var g struct {
		AccessToken *string  `json:"access_token"`
		ExpiresIn   *float64 `json:"expires_in"`
		TokenType   *string  `json:"token_type"`
	}
	if json.Unmarshal(body, &g) != nil || g.AccessToken == nil || g.ExpiresIn == nil || g.TokenType == nil {
		return Token{}, 0, false
	}
	if *g.ExpiresIn < 0 || *g.ExpiresIn > math.MaxUint32 || !strings.EqualFold(*g.TokenType, "bearer") {
		return Token{}, 0, false
	}
	return Token{*g.AccessToken}, time.Duration(*g.ExpiresIn * float64(time.Second)), true
}
