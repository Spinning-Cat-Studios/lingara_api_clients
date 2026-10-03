package lingara

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// The webhook verifier (CONTRACT.md appendix W; ADR 30.9.26aa D4): Standard
// Webhooks with a lgr_whsec_ secret, built on the standard library alone.

// secretPrefix opens every Lingara webhook secret.
const secretPrefix = "lgr_whsec_"

// webhookTolerance is the specification's, and is not configurable: a wider
// window only widens replay.
const webhookTolerance = 300

// paddedBase64 is the secret's remainder, matched before decoding, since
// decoders differ in what they let through.
var paddedBase64 = regexp.MustCompile(`^[A-Za-z0-9+/]+={0,2}$`)

// WebhookFailure is why a delivery did not verify.
type WebhookFailure string

// The six reasons, spelled as the contract spells them.
const (
	MissingHeader       WebhookFailure = "missing_header"
	MalformedHeader     WebhookFailure = "malformed_header"
	TimestampTooOld     WebhookFailure = "timestamp_too_old"
	TimestampTooNew     WebhookFailure = "timestamp_too_new"
	NoMatchingSignature WebhookFailure = "no_matching_signature"
	MalformedPayload    WebhookFailure = "malformed_payload"
)

// WebhookVerificationError is a delivery that did not verify. It is not one
// of the four API error types and does not implement Error: no Lingara server
// answered anything, and a handler for API errors must not swallow a forged
// webhook. Its message never holds a secret, a signature or the body.
type WebhookVerificationError struct {
	Reason WebhookFailure
}

func (e *WebhookVerificationError) Error() string {
	return "lingara: webhook verification failed: " + string(e.Reason)
}

func webhookFailed(reason WebhookFailure) error { return &WebhookVerificationError{Reason: reason} }

// Webhook verifies Lingara's webhook deliveries. It is safe for concurrent
// use, and it stores nothing: deduplicate by the event's ID yourself.
type Webhook struct {
	keys  [][]byte
	clock func() time.Time
}

// NewWebhook takes the endpoint's secret, or both during a rotation. Each must
// be lgr_whsec_ followed by padded standard base64 of at least 24 bytes.
func NewWebhook(secrets ...string) (*Webhook, error) {
	if len(secrets) == 0 {
		return nil, errors.New("lingara: NewWebhook needs a secret")
	}
	keys := make([][]byte, 0, len(secrets))
	for i, secret := range secrets {
		key, err := webhookKey(secret)
		if err != nil {
			return nil, fmt.Errorf("lingara: webhook secret %d: %w", i+1, err)
		}
		keys = append(keys, key)
	}
	return &Webhook{keys: keys, clock: time.Now}, nil
}

// webhookKey is a secret's HMAC key: its decoded remainder. The errors never
// quote the secret.
func webhookKey(secret string) ([]byte, error) {
	rest, ok := strings.CutPrefix(secret, secretPrefix)
	switch {
	case !ok:
		return nil, errors.New("it does not start with lgr_whsec_")
	case len(rest)%4 != 0 || !paddedBase64.MatchString(rest):
		return nil, errors.New("it is not padded standard base64 after lgr_whsec_")
	}
	key, err := base64.StdEncoding.DecodeString(rest)
	if err != nil {
		return nil, errors.New("it is not padded standard base64 after lgr_whsec_")
	}
	if len(key) < 24 {
		return nil, errors.New("it decodes to fewer than 24 bytes")
	}
	return key, nil
}

// WithClock returns a copy of w that reads the time from now: the client's
// clock seam, for testing.
func (w *Webhook) WithClock(now func() time.Time) *Webhook {
	return &Webhook{keys: w.keys, clock: now}
}

// Verify checks a delivery and returns its event. body is the request body
// exactly as received, read before anything parses it; h is its headers, read
// case-insensitively. A failure is a *WebhookVerificationError.
func (w *Webhook) Verify(body []byte, h http.Header) (Event, error) {
	id, err := w.check(body, h)
	if err != nil {
		return nil, err
	}
	ev, err := ParseEvent(body)
	if err != nil || ev.Meta().ID != id {
		return nil, webhookFailed(MalformedPayload)
	}
	return ev, nil
}

// VerifySignature checks a delivery's signature and timestamp only, for a
// signed body that is not an event envelope (an app-kit request). It never
// fails with MalformedPayload.
func (w *Webhook) VerifySignature(body []byte, h http.Header) error {
	_, err := w.check(body, h)
	return err
}

// check runs the headers, the tolerance and the signature, in that order, and
// returns webhook-id.
func (w *Webhook) check(body []byte, h http.Header) (string, error) {
	id, okID := headerValue(h, "webhook-id")
	stamp, okStamp := headerValue(h, "webhook-timestamp")
	signatures, okSig := headerValue(h, "webhook-signature")
	if !okID || !okStamp || !okSig {
		return "", webhookFailed(MissingHeader)
	}
	if err := w.checkTimestamp(stamp); err != nil {
		return "", err
	}
	if !w.signed(id+"."+stamp+".", body, signatures) {
		return "", webhookFailed(NoMatchingSignature)
	}
	return id, nil
}

func (w *Webhook) checkTimestamp(stamp string) error {
	if stamp == "" || strings.Trim(stamp, "0123456789") != "" {
		return webhookFailed(MalformedHeader)
	}
	sent, err := strconv.ParseInt(stamp, 10, 64)
	if err != nil {
		// All digits, so out of range: far in the future.
		return webhookFailed(TimestampTooNew)
	}
	now := w.clock().Unix()
	switch {
	case now-sent > webhookTolerance:
		return webhookFailed(TimestampTooOld)
	case sent-now > webhookTolerance:
		return webhookFailed(TimestampTooNew)
	}
	return nil
}

// signed reports whether any v1 signature in the space-separated list is any
// key's HMAC-SHA256 of prefix and body. hmac.Equal compares in constant time;
// another version, or one that does not decode, is skipped.
func (w *Webhook) signed(prefix string, body []byte, signatures string) bool {
	expected := make([][]byte, len(w.keys))
	for i, key := range w.keys {
		mac := hmac.New(sha256.New, key)
		mac.Write([]byte(prefix))
		mac.Write(body)
		expected[i] = mac.Sum(nil)
	}
	for _, element := range strings.Split(signatures, " ") {
		version, encoded, ok := strings.Cut(element, ",")
		if !ok || version != "v1" {
			continue
		}
		sig, err := base64.StdEncoding.DecodeString(encoded)
		if err != nil {
			continue
		}
		for _, want := range expected {
			if hmac.Equal(sig, want) {
				return true
			}
		}
	}
	return false
}

// headerValue reads a header case-insensitively, including from a map built
// by hand with non-canonical keys.
func headerValue(h http.Header, name string) (string, bool) {
	if values, ok := h[http.CanonicalHeaderKey(name)]; ok && len(values) > 0 {
		return values[0], true
	}
	for key, values := range h {
		if strings.EqualFold(key, name) && len(values) > 0 {
			return values[0], true
		}
	}
	return "", false
}

// Redaction (ADR 29.9.26q D5): the keys never render.

func (w *Webhook) Format(f fmt.State, verb rune) {
	writeFormatted(f, verb, rendering{
		plain:    fmt.Sprintf("lingara.Webhook(%d secrets)", len(w.keys)),
		detailed: fmt.Sprintf("&lingara.Webhook{Secrets:%s}", redacted),
	})
}

func (w *Webhook) LogValue() slog.Value {
	return slog.GroupValue(slog.Int("secrets", len(w.keys)), slog.String("secret", redacted))
}
