package lingara

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Client calls the Lingara API. Build one with New; it is safe for
// concurrent use.
type Client struct {
	http      *http.Client
	baseURL   string
	version   string
	userAgent string
	policy    retryPolicy
	idle      time.Duration
	tailMax   int
	tokens    TokenSource
	versions  *versionObserver
}

// Result is a JSON operation's value and the version it was served under.
type Result[T any] struct {
	Value T
	// ServedVersion is the Lingara-Version echo, or "" when there was none.
	ServedVersion string
}

// route is one operation's method and path, generated into routes_gen.go.
type route struct {
	method     string
	path       string
	needsToken bool
	stream     *streamRoute
}

// New builds a client. It returns an error for WithVersion(""), and for
// WithTokenSource beside WithClientCredentials.
func New(opts ...Option) (*Client, error) {
	o := defaultOptions()
	for _, opt := range opts {
		opt(&o)
	}
	if err := o.validate(); err != nil {
		return nil, err
	}
	c := &Client{
		http:      o.http,
		baseURL:   strings.TrimRight(o.baseURL, "/"),
		userAgent: userAgent(o.userAgent),
		policy:    retryPolicy{maxAttempts: o.maxAttempts, retryAfterCap: o.retryAfterCap, clock: o.clock, sleep: o.sleep},
		idle:      o.idleTimeout,
		tailMax:   o.tailMax,
		tokens:    o.tokenSource,
		versions:  newVersionObserver(o.hook),
	}
	if o.version != nil {
		c.version = *o.version
	}
	if o.hasCredentials {
		c.tokens = newClientCredentials(o.clientID, o.secret, exchangeConfig{
			http: o.http, tokenURL: o.tokenURL, userAgent: c.userAgent, secretPost: o.secretPost,
			scopes: o.scopes, policy: c.policy, timeout: o.tokenTimeout,
		})
	}
	return c, nil
}

// request is one /v1 request, before auth.
type request struct {
	method     string
	url        string
	body       []byte
	accept     string
	needsToken bool
	// header is sent on every attempt: Idempotency-Key (K4) and
	// Last-Event-ID (K5a), ADR 30.9.26aa.
	header http.Header
	// single sends one attempt and hands a 429 or 503 back as its error:
	// a tail open bypasses K4's attempt loop (CONTRACT.md K5a).
	single bool
}

// send runs the pipeline: auth and the one 401 retry, the Retry-After loop,
// and the error mapping. It returns a 2xx response. reqCtx carries the
// request; ctx is the caller's, which decides whether a failure is theirs.
// A client with no token source sends an operation that needs one without
// Authorization, and the server's 401 is the answer.
func (c *Client) send(ctx, reqCtx context.Context, r request) (*http.Response, error) {
	policy := c.policy
	if r.single {
		policy.maxAttempts = 1
	}
	attempts := func(tok *Token) (*http.Response, error) {
		return withRetries(ctx, policy, func() (*http.Response, error) { return c.sendOnce(ctx, reqCtx, r, tok) })
	}
	var res *http.Response
	var err error
	if c.tokens != nil && r.needsToken {
		res, err = withTokenRetry(ctx, c.tokens, func(tok Token) (*http.Response, error) { return attempts(&tok) })
	} else {
		res, err = attempts(nil)
	}
	if err != nil {
		return nil, err
	}
	if res.StatusCode >= 200 && res.StatusCode <= 299 {
		return res, nil
	}
	return nil, readRefusal(endpointV1, res, c.policy.clock())
}

func (c *Client) sendOnce(ctx, reqCtx context.Context, r request, tok *Token) (*http.Response, error) {
	var body io.Reader
	if r.body != nil {
		body = bytes.NewReader(r.body)
	}
	req, err := http.NewRequestWithContext(reqCtx, r.method, r.url, body)
	if err != nil {
		return nil, &TransportError{Kind: Connect, Err: err}
	}
	for name, values := range r.header {
		req.Header[name] = values
	}
	req.Header.Set("Accept", r.accept)
	req.Header.Set("User-Agent", c.userAgent)
	if r.body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	secret := ""
	if tok != nil {
		secret = tok.raw
		req.Header.Set("Authorization", "Bearer "+tok.raw)
	}
	if c.version != "" {
		req.Header.Set("Lingara-Version", c.version)
	}
	res, err := c.http.Do(req)
	if err != nil {
		return nil, transportError(ctx, err, false, secret)
	}
	return res, nil
}

// getJSON runs one JSON operation. The deprecation hook, if any, is called
// before it returns.
func getJSON[T any](ctx context.Context, c *Client, operationID, id string) (*Result[T], error) {
	rt := routes[operationID]
	return sendJSON[T](ctx, c, request{method: rt.method, url: c.url(rt.path, id), accept: "application/json", needsToken: rt.needsToken})
}

// sendJSON sends one JSON request and decodes its 2xx body. The deprecation
// hook, if any, is called before it returns.
func sendJSON[T any](ctx context.Context, c *Client, r request) (*Result[T], error) {
	res, err := c.send(ctx, ctx, r)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	served := c.versions.observe(res.Header, res.Request.URL)
	body, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, transportError(ctx, err, true)
	}
	var value T
	if err := json.Unmarshal(body, &value); err != nil {
		return nil, &TransportError{Kind: MalformedResponse, Err: err}
	}
	return &Result[T]{Value: value, ServedVersion: served}, nil
}

// streamCall names one stream operation and its input: a path id, or a body,
// and any query, extra headers or single attempt (a tail open, K5a).
type streamCall struct {
	operationID string
	id          string
	body        any
	query       url.Values
	header      http.Header
	single      bool
}

// openStream sends a stream operation's request eagerly and returns once its
// headers are in: a refusal is this call's error, an in-stream failure the
// stream's last pair. The deprecation hook, if any, is called first.
func openStream[E any](ctx context.Context, c *Client, op streamCall, decode func(string, []byte) (E, bool, error)) (*Stream[E], error) {
	rt := routes[op.operationID]
	var payload []byte
	if op.body != nil {
		var err error
		if payload, err = json.Marshal(op.body); err != nil {
			return nil, fmt.Errorf("lingara: %s: encoding the request body: %w", op.operationID, err)
		}
	}
	reqCtx, cancel := context.WithCancelCause(ctx)
	res, err := c.send(ctx, reqCtx, request{
		method: rt.method, url: withQuery(c.url(rt.path, op.id), op.query), body: payload, accept: "text/event-stream",
		needsToken: rt.needsToken, header: op.header, single: op.single,
	})
	if err != nil {
		cancel(errClosed)
		return nil, err
	}
	if mediaType(res.Header) != "text/event-stream" {
		res.Body.Close()
		cancel(errClosed)
		return nil, &TransportError{Kind: MalformedResponse}
	}
	served := c.versions.observe(res.Header, res.Request.URL)
	return &Stream[E]{
		route: rt.stream, decode: decode, body: res.Body, ctx: ctx, reqCtx: reqCtx,
		cancel: cancel, idle: c.idle, servedVersion: served,
	}, nil
}

// url is the base URL and the route's path, with {id} percent-encoded.
func (c *Client) url(path, id string) string {
	return c.baseURL + strings.ReplaceAll(path, "{id}", encodeSegment(id))
}

// withQuery appends a query string, when there is one.
func withQuery(u string, q url.Values) string {
	if len(q) == 0 {
		return u
	}
	return u + "?" + q.Encode()
}

// encodeSegment percent-encodes everything but A–Z a–z 0–9 - . _ ~, as the
// other libraries do.
func encodeSegment(s string) string {
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if ('a' <= c && c <= 'z') || ('A' <= c && c <= 'Z') || ('0' <= c && c <= '9') || strings.IndexByte("-._~", c) >= 0 {
			b.WriteByte(c)
		} else {
			fmt.Fprintf(&b, "%%%02X", c)
		}
	}
	return b.String()
}
