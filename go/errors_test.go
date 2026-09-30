package lingara

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"syscall"
	"testing"
	"time"
)

var mappedAt = time.Unix(1_790_000_000, 0)

// refuse maps one refusal: its status, Content-Type and body.
func refuse(ep endpoint, status int, contentType, body string) error {
	h := http.Header{}
	if contentType != "" {
		h.Set("Content-Type", contentType)
	}
	return refusalError(ep, &http.Response{StatusCode: status, Header: h}, []byte(body), mappedAt)
}

// maintenanceWith matches a *MaintenanceError whose body is exactly body: a
// 1 KiB cut of a longer one lands on a character boundary.
func maintenanceWith(body string) func(error) bool {
	return func(err error) bool {
		var m *MaintenanceError
		return errors.As(err, &m) && m.Body == body
	}
}

func apiWith(status int, code string) func(error) bool {
	return func(err error) bool {
		var api *APIError
		return errors.As(err, &api) && api.Status == status && api.Code == code
	}
}

// oauthWith matches an *OAuthError with no description.
func oauthWith(status int, code string) func(error) bool {
	return func(err error) bool {
		var oauth *OAuthError
		return errors.As(err, &oauth) && oauth.Status == status && oauth.ErrorCode == code && oauth.Description == ""
	}
}

// TestResponsesMapToTheErrorFamily: 29.9.26q AC13. A plain-text 503 from
// either endpoint is a *MaintenanceError; a non-envelope /v1 502 is an
// *APIError with Code http_502; a non-RFC 6749 token-endpoint 500 is an
// *OAuthError with http_500; each is reachable by errors.As through Error.
func TestResponsesMapToTheErrorFamily(t *testing.T) {
	maintenance := "Service is under maintenance. Please try again later."
	cases := []struct {
		name              string
		ep                endpoint
		status            int
		contentType, body string
		matches           func(error) bool
	}{
		{"a /v1 plain-text 503", endpointV1, 503, "text/plain; charset=utf-8", maintenance, maintenanceWith(maintenance)},
		{"a token plain-text 503", endpointToken, 503, "text/plain; charset=utf-8", maintenance, maintenanceWith(maintenance)},
		{"a 1200-byte 503 body", endpointV1, 503, "text/html", strings.Repeat("é", 600), maintenanceWith(strings.Repeat("é", 512))},
		{"a non-envelope 502", endpointV1, 502, "text/html", "<html>Bad gateway</html>", apiWith(502, "http_502")},
		{"an envelope 403", endpointV1, 403, "application/json", `{"code":"insufficient_scope","error":"Needs usage:read."}`, apiWith(403, "insufficient_scope")},
		{"a non-RFC 6749 500", endpointToken, 500, "", "", oauthWith(500, "http_500")},
	}
	for _, c := range cases {
		if err := refuse(c.ep, c.status, c.contentType, c.body); !c.matches(err) {
			t.Errorf("%s mapped to %#v", c.name, err)
		}
	}
	for _, err := range []error{&APIError{}, &OAuthError{}, &MaintenanceError{}, &TransportError{}} {
		var family Error
		if wrapped := errors.Join(errors.New("context"), err); !errors.As(wrapped, &family) {
			t.Errorf("%T is not reachable through lingara.Error", err)
		}
	}
}

type timeoutError struct{}

func (timeoutError) Error() string   { return "i/o timeout" }
func (timeoutError) Timeout() bool   { return true }
func (timeoutError) Temporary() bool { return true }

// realFailure runs one request that fails for real and returns Do's error.
func realFailure(t *testing.T, client *http.Client, url string) error {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, url, nil)
	res, err := client.Do(req)
	if err == nil {
		res.Body.Close()
		t.Fatalf("%s: the request succeeded", url)
	}
	return err
}

type failure struct {
	name         string
	err          error
	afterHeaders bool
	want         TransportKind
}

// failures is AC14's table: real failures where a test can cause one, and
// Go's own error types where it cannot (a DNS failure, an alert, a reset).
func failures(t *testing.T) []failure {
	l, _ := net.Listen("tcp", "127.0.0.1:0")
	closedPort := "http://" + l.Addr().String()
	l.Close()
	tlsServer := httptest.NewTLSServer(http.NotFoundHandler())
	t.Cleanup(tlsServer.Close)
	slow := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) { <-r.Context().Done() }))
	t.Cleanup(slow.Close)
	dial := func(err error) error {
		return &url.Error{Op: "Get", Err: &net.OpError{Op: "dial", Net: "tcp", Err: err}}
	}
	return []failure{
		{"refused dial", realFailure(t, &http.Client{}, closedPort), false, Connect},
		{"DNS failure", dial(&net.DNSError{Err: "no such host", Name: "api.invalid", IsNotFound: true}), false, Connect},
		{"certificate error", realFailure(t, &http.Client{}, tlsServer.URL), false, TLS},
		{"TLS alert", &url.Error{Op: "Get", Err: tls.AlertError(40)}, false, TLS},
		{"http.Client.Timeout", realFailure(t, &http.Client{Timeout: 50 * time.Millisecond}, slow.URL), false, Timeout},
		{"dial that times out", dial(timeoutError{}), false, Connect},
		{"io.EOF from Do", &url.Error{Op: "Get", Err: io.EOF}, false, Connect},
		{"reset while reading", &net.OpError{Op: "read", Err: syscall.ECONNRESET}, true, Reset},
		{"unexpected EOF while reading", io.ErrUnexpectedEOF, true, Reset},
	}
}

// TestTransportFailuresMapToKinds: 29.9.26q AC14. A refused dial, a DNS
// failure, a certificate error, a TLS alert, an http.Client.Timeout, a
// reset, an io.EOF from Do and an unexpected EOF while reading each map to
// their C2 D4 Kind; a dial that times out and an io.EOF from Do are both
// Connect.
func TestTransportFailuresMapToKinds(t *testing.T) {
	for _, c := range failures(t) {
		err := transportError(context.Background(), c.err, c.afterHeaders)
		var te *TransportError
		if !errors.As(err, &te) || te.Kind != c.want {
			t.Errorf("%s (%v): got %v, want Kind %s", c.name, c.err, err, c.want)
		}
	}
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	if err := transportError(cancelled, io.EOF, true); !errors.Is(err, context.Canceled) {
		t.Errorf("a cancelled call mapped to %v, want context.Canceled", err)
	}
	if err := transportError(context.Background(), errors.New("echo "+testSecret), false, testSecret); strings.Contains(err.Error(), testSecret) {
		t.Errorf("the wrapped error was not scrubbed: %v", err)
	}
}
