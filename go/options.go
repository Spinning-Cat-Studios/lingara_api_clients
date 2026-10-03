package lingara

import (
	"context"
	"errors"
	"net/http"
	"time"
)

// Option configures a Client. Every C2 knob is one (ADR 29.9.26q D3).
type Option func(*options)

type options struct {
	clientID       string
	secret         ClientSecret
	hasCredentials bool
	secretPost     bool
	scopes         []string
	tokenSource    TokenSource
	baseURL        string
	tokenURL       string
	version        *string
	hook           func(DeprecationNotice)
	maxAttempts    int
	retryAfterCap  time.Duration
	idleTimeout    time.Duration
	tailMax        int
	tokenTimeout   time.Duration
	userAgent      string
	clock          func() time.Time
	sleep          func(context.Context, time.Duration) error
	http           *http.Client
}

func defaultOptions() options {
	return options{
		baseURL:       "https://api.getlingara.com",
		tokenURL:      "https://api.getlingara.com/oauth/token",
		maxAttempts:   3,
		retryAfterCap: 60 * time.Second,
		idleTimeout:   120 * time.Second,
		tailMax:       8,
		tokenTimeout:  30 * time.Second,
		clock:         time.Now,
		sleep:         realSleep,
		// Never http.DefaultClient, and no Timeout: one would cap a whole
		// stream. The stream enforces its own idle timeout.
		http: &http.Client{},
	}
}

func (o *options) validate() error {
	switch {
	case o.version != nil && *o.version == "":
		return errors.New("lingara: WithVersion needs a version id")
	case o.tokenSource != nil && o.hasCredentials:
		return errors.New("lingara: WithTokenSource and WithClientCredentials are exclusive")
	case o.maxAttempts < 1:
		return errors.New("lingara: WithMaxAttempts needs at least 1")
	case o.tailMax < 1:
		return errors.New("lingara: WithTailMaxFailures needs at least 1")
	}
	return nil
}

// WithClientCredentials authenticates with the client-credentials grant.
// Omit it for a credential-free client, which can call the three operations
// that need no token (K1).
func WithClientCredentials(clientID, clientSecret string) Option {
	return func(o *options) {
		o.clientID, o.secret, o.hasCredentials = clientID, NewClientSecret(clientSecret), true
	}
}

// WithClientSecretPost sends the credentials in the token request's form body
// (client_secret_post) instead of the default client_secret_basic header.
func WithClientSecretPost() Option { return func(o *options) { o.secretPost = true } }

// WithScopes asks the token endpoint for these scopes. Without it no scope
// parameter is sent, which means every scope the client is allowed.
func WithScopes(scopes ...string) Option {
	return func(o *options) { o.scopes = append([]string(nil), scopes...) }
}

// WithTokenSource replaces the default ClientCredentials with a caller's own.
// It cannot be combined with WithClientCredentials.
func WithTokenSource(ts TokenSource) Option { return func(o *options) { o.tokenSource = ts } }

// WithBaseURL sets the API's base URL; the default is https://api.getlingara.com.
func WithBaseURL(u string) Option { return func(o *options) { o.baseURL = u } }

// WithTokenURL sets the token endpoint; the default is
// https://api.getlingara.com/oauth/token.
func WithTokenURL(u string) Option { return func(o *options) { o.tokenURL = u } }

// WithVersion pins every /v1 request to a Lingara-Version (K2). The id is not
// validated beyond non-empty: the server's 400 api_version_unknown answers.
func WithVersion(id string) Option { return func(o *options) { o.version = &id } }

// WithDeprecationHook is called once per response under a deprecated
// version. Without one, the client logs one slog.Warn per version id.
func WithDeprecationHook(hook func(DeprecationNotice)) Option {
	return func(o *options) { o.hook = hook }
}

// WithMaxAttempts sets tries per HTTP request, the first included: 3 by
// default, and 1 turns retries off (K4).
func WithMaxAttempts(n int) Option { return func(o *options) { o.maxAttempts = n } }

// WithRetryAfterCap sets the longest Retry-After the client will sleep: 60 s
// by default. A longer one is returned in the error's RetryAfter instead.
func WithRetryAfterCap(d time.Duration) Option { return func(o *options) { o.retryAfterCap = d } }

// WithStreamIdleTimeout fails a stream after this long with no byte while a
// read is pending: 120 s by default (K5).
func WithStreamIdleTimeout(d time.Duration) Option { return func(o *options) { o.idleTimeout = d } }

// WithTailMaxFailures sets how many consecutive failed opens TailEvents rides
// out before it returns the last: 8 by default, which sleeps 1, 2, 4, 8, 16,
// 30 and 30 s between them (CONTRACT.md K5a; ADR 30.9.26aa D7).
func WithTailMaxFailures(n int) Option { return func(o *options) { o.tailMax = n } }

// WithTokenRequestTimeout bounds each HTTP attempt of the token exchange: 30 s
// by default. Retry-After sleeps between attempts are not counted.
func WithTokenRequestTimeout(d time.Duration) Option {
	return func(o *options) { o.tokenTimeout = d }
}

// WithUserAgentSuffix appends a product token, after one space, to the
// library's own User-Agent, which always comes first (K6).
func WithUserAgentSuffix(suffix string) Option { return func(o *options) { o.userAgent = suffix } }

// WithClock replaces time.Now. It is a testing seam.
func WithClock(now func() time.Time) Option { return func(o *options) { o.clock = now } }

// WithSleeper replaces the Retry-After sleep, which must return ctx.Err()
// when ctx is done. It is a testing seam.
func WithSleeper(sleep func(context.Context, time.Duration) error) Option {
	return func(o *options) { o.sleep = sleep }
}

// WithHTTPClient sets the HTTP client for transport, proxy and TLS settings.
// A Timeout on it caps every call, streams included.
func WithHTTPClient(c *http.Client) Option { return func(o *options) { o.http = c } }
