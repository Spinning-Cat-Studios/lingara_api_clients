package lingara

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"
)

// K3: one error family (CONTRACT.md K3; ADR 29.9.26q D4). Every error a call
// returns, cancellation aside, is one of four pointer types, and all four
// implement Error, so errors.As handles them together. Cancellation is never
// one of them: it is ctx.Err(), so errors.Is(err, context.Canceled) holds.

// Error is the marker interface the four error types implement.
type Error interface {
	error
	lingaraError()
}

// APIError is a /v1 refusal, or a stream's error event (then Status is 200).
type APIError struct {
	Status int
	// Code is the envelope's code, or http_<status> when the body is not one.
	Code    string
	Message string
	// RetryAfter is the Retry-After the library declined to wait for, or nil.
	RetryAfter *time.Duration
	// PlanID is set only by an error event that carried one.
	PlanID        string
	ServedVersion string
}

// OAuthError is a token-endpoint refusal: RFC 6749 §5.2, or http_<status>.
// Its `error` field is spelled ErrorCode, since a Go type cannot have both a
// field and a method named Error.
type OAuthError struct {
	Status    int
	ErrorCode string
	// Description is error_description, or "".
	Description string
	RetryAfter  *time.Duration
}

// MaintenanceError is a 503 whose body is not JSON: the service is in
// maintenance.
type MaintenanceError struct {
	// Body is the response text, at most 1 KiB, cut on a character boundary.
	Body       string
	RetryAfter *time.Duration
}

// TransportError is a call with no usable HTTP answer.
type TransportError struct {
	Kind TransportKind
	// Err is the underlying failure, when there is one. It is replaced by a
	// scrubbed error if its text ever contains the secret or a token.
	Err error
}

// TransportKind is C2 D4's reason a call had no usable answer.
type TransportKind string

// The seven transport kinds, spelled as the contract spells them.
const (
	Connect           TransportKind = "connect"
	TLS               TransportKind = "tls"
	Reset             TransportKind = "reset"
	Timeout           TransportKind = "timeout"
	StreamEndedEarly  TransportKind = "stream_ended_early"
	MalformedResponse TransportKind = "malformed_response"
	MalformedEvent    TransportKind = "malformed_event"
)

func (*APIError) lingaraError()         {}
func (*OAuthError) lingaraError()       {}
func (*MaintenanceError) lingaraError() {}
func (*TransportError) lingaraError()   {}

func (e *APIError) Error() string {
	return fmt.Sprintf("lingara: %s: %s (HTTP %d)", e.Code, e.Message, e.Status)
}

func (e *OAuthError) Error() string {
	if e.Description == "" {
		return fmt.Sprintf("lingara: token endpoint: %s (HTTP %d)", e.ErrorCode, e.Status)
	}
	return fmt.Sprintf("lingara: token endpoint: %s: %s (HTTP %d)", e.ErrorCode, e.Description, e.Status)
}

func (e *MaintenanceError) Error() string { return "lingara: the API is under maintenance" }

func (e *TransportError) Error() string {
	if e.Err == nil {
		return "lingara: transport failure: " + string(e.Kind)
	}
	return fmt.Sprintf("lingara: transport failure: %s: %v", e.Kind, e.Err)
}

// Unwrap returns the underlying failure.
func (e *TransportError) Unwrap() error { return e.Err }

// ── Response → error ─────────────────────────────────────────────────────

const maintenanceBodyBytes = 1024

// endpoint says which endpoint refused: the two map their bodies differently.
type endpoint int

const (
	endpointV1 endpoint = iota
	endpointToken
)

// refusalError maps a non-2xx response and its body to its K3 type, in C2
// D4's precedence: a non-JSON 503 from either endpoint is maintenance first,
// then the endpoint's own body shape.
func refusalError(ep endpoint, res *http.Response, body []byte, now time.Time) error {
	retryAfter := parseRetryAfter(res.Header, now)
	if res.StatusCode == http.StatusServiceUnavailable && !isJSON(res.Header) {
		return &MaintenanceError{Body: truncateUTF8(string(body), maintenanceBodyBytes), RetryAfter: retryAfter}
	}
	var fields map[string]any
	_ = json.Unmarshal(body, &fields)
	text := func(key string) (string, bool) { s, ok := fields[key].(string); return s, ok }
	if ep == endpointToken {
		e := &OAuthError{Status: res.StatusCode, ErrorCode: fmt.Sprintf("http_%d", res.StatusCode), RetryAfter: retryAfter}
		if code, ok := text("error"); ok {
			e.ErrorCode = code
			e.Description, _ = text("error_description")
		}
		return e
	}
	e := &APIError{Status: res.StatusCode, RetryAfter: retryAfter, ServedVersion: res.Header.Get("Lingara-Version")}
	code, hasCode := text("code")
	message, hasMessage := text("error")
	if hasCode && hasMessage {
		e.Code, e.Message = code, message
	} else {
		e.Code, e.Message = fmt.Sprintf("http_%d", res.StatusCode), fmt.Sprintf("HTTP %d", res.StatusCode)
	}
	return e
}

// readRefusal reads a refused response's body and maps it.
func readRefusal(ep endpoint, res *http.Response, now time.Time) error {
	defer res.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(res.Body, 64<<10))
	return refusalError(ep, res, body, now)
}

// mediaType is the Content-Type's media type, parameters dropped.
func mediaType(h http.Header) string {
	media, _, err := mime.ParseMediaType(h.Get("Content-Type"))
	if err != nil {
		return ""
	}
	return media
}

func isJSON(h http.Header) bool {
	media := mediaType(h)
	return media == "application/json" || strings.HasSuffix(media, "+json")
}

func truncateUTF8(s string, max int) string {
	if len(s) <= max {
		return s
	}
	end := max
	for end > 0 && !utf8.RuneStart(s[end]) {
		end--
	}
	return s[:end]
}

// ── A failed request → a transport kind ──────────────────────────────────

// transportError maps a failed Do (afterHeaders false) or body read (true).
// ctx is the caller's context: when it is done, its own error is returned,
// never a K3 type. secrets are scrubbed from the wrapped error (D5).
func transportError(ctx context.Context, err error, afterHeaders bool, secrets ...string) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}
	return &TransportError{Kind: transportKind(err, afterHeaders), Err: scrub(err, secrets...)}
}

// transportKind reads Go's typed network errors, never their text, in D4a's
// order. A dial that times out is connect: timeout is kept for the library's
// own waits on a server it reached.
func transportKind(err error, afterHeaders bool) TransportKind {
	switch {
	case isTLSFailure(err):
		return TLS
	case isConnectFailure(err):
		return Connect
	case isTimeout(err):
		return Timeout
	case afterHeaders:
		// syscall.ECONNRESET, syscall.EPIPE and io.ErrUnexpectedEOF, and any
		// other failure while reading a body.
		return Reset
	default:
		// Any other failure of Do, an io.EOF before the status line included.
		return Connect
	}
}

func isTLSFailure(err error) bool {
	var verify *tls.CertificateVerificationError
	var unknownAuthority x509.UnknownAuthorityError
	var hostname x509.HostnameError
	var invalid x509.CertificateInvalidError
	var header tls.RecordHeaderError
	var alert tls.AlertError
	return errors.As(err, &verify) || errors.As(err, &unknownAuthority) || errors.As(err, &hostname) ||
		errors.As(err, &invalid) || errors.As(err, &header) || errors.As(err, &alert)
}

func isConnectFailure(err error) bool {
	var dns *net.DNSError
	var op *net.OpError
	return errors.As(err, &dns) || (errors.As(err, &op) && op.Op == "dial")
}

func isTimeout(err error) bool {
	var timeout interface{ Timeout() bool }
	return errors.Is(err, context.DeadlineExceeded) || (errors.As(err, &timeout) && timeout.Timeout())
}

// scrubbedError stands in for an underlying error whose text named a secret.
type scrubbedError struct{}

func (scrubbedError) Error() string {
	return "the underlying error was withheld: it contained a credential"
}

// scrub returns err, or a scrubbed stand-in if its text contains any secret.
// net/http does not echo request headers or bodies, so this is defence in
// depth; only then is errors.Is against the original lost.
func scrub(err error, secrets ...string) error {
	text := err.Error()
	for _, s := range secrets {
		if s != "" && strings.Contains(text, s) {
			return scrubbedError{}
		}
	}
	return err
}

// ── Rendering (D5) ───────────────────────────────────────────────────────

func (e *APIError) Format(f fmt.State, verb rune) {
	writeFormatted(f, verb, rendering{e.Error(), fmt.Sprintf("&lingara.APIError{Status:%d, Code:%q, Message:%q, RetryAfter:%s, PlanID:%q, ServedVersion:%q}",
		e.Status, e.Code, e.Message, durationText(e.RetryAfter), e.PlanID, e.ServedVersion)})
}

func (e *OAuthError) Format(f fmt.State, verb rune) {
	writeFormatted(f, verb, rendering{e.Error(), fmt.Sprintf("&lingara.OAuthError{Status:%d, ErrorCode:%q, Description:%q, RetryAfter:%s}",
		e.Status, e.ErrorCode, e.Description, durationText(e.RetryAfter))})
}

func (e *MaintenanceError) Format(f fmt.State, verb rune) {
	writeFormatted(f, verb, rendering{e.Error(), fmt.Sprintf("&lingara.MaintenanceError{Body:%q, RetryAfter:%s}", e.Body, durationText(e.RetryAfter))})
}

func (e *TransportError) Format(f fmt.State, verb rune) {
	writeFormatted(f, verb, rendering{e.Error(), fmt.Sprintf("&lingara.TransportError{Kind:%q, Err:%q}", e.Kind, errText(e.Err))})
}

func (e *APIError) LogValue() slog.Value {
	return slog.GroupValue(slog.Int("status", e.Status), slog.String("code", e.Code), slog.String("message", e.Message),
		slog.String("plan_id", e.PlanID), slog.String("served_version", e.ServedVersion))
}

func (e *OAuthError) LogValue() slog.Value {
	return slog.GroupValue(slog.Int("status", e.Status), slog.String("error", e.ErrorCode), slog.String("description", e.Description))
}

func (e *MaintenanceError) LogValue() slog.Value {
	return slog.GroupValue(slog.String("body", e.Body))
}

func (e *TransportError) LogValue() slog.Value {
	return slog.GroupValue(slog.String("kind", string(e.Kind)), slog.String("err", errText(e.Err)))
}

func durationText(d *time.Duration) string {
	if d == nil {
		return "nil"
	}
	return d.String()
}

func errText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
