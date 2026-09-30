package lingara

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"testing"
)

// renderings is every way a value can be printed or logged: five fmt verbs
// and slog's text and JSON handlers.
func renderings(v any) []string {
	var out []string
	for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%q"} {
		out = append(out, fmt.Sprintf(verb, v))
	}
	for _, handler := range []func(*bytes.Buffer) slog.Handler{
		func(b *bytes.Buffer) slog.Handler { return slog.NewTextHandler(b, nil) },
		func(b *bytes.Buffer) slog.Handler { return slog.NewJSONHandler(b, nil) },
	} {
		var buf bytes.Buffer
		slog.New(handler(&buf)).Info("render", "value", v)
		out = append(out, buf.String())
	}
	return out
}

func assertNoSecret(t *testing.T, name string, v any, secrets []string) {
	t.Helper()
	for _, r := range renderings(v) {
		for _, s := range secrets {
			if strings.Contains(r, s) {
				t.Errorf("%s: %q shows a secret", name, r)
			}
		}
	}
}

// assertClientIDShown: the client_id is not secret and is rendered, beside
// a [REDACTED] secret.
func assertClientIDShown(t *testing.T, name string, v any) {
	t.Helper()
	for _, r := range renderings(v) {
		if !strings.Contains(r, testClientID) {
			t.Errorf("%s: %q does not render the client_id", name, r)
		}
	}
	if r := fmt.Sprintf("%+v", v); !strings.Contains(r, "[REDACTED]") {
		t.Errorf("%s: %q does not say [REDACTED]", name, r)
	}
}

// TestSecretAndTokenRedactedInEveryForm: 29.9.26q AC12. %v, %+v, %#v, %s,
// %q and slog text and JSON renderings of the *Client, the
// *ClientCredentials and each error show [REDACTED] and never the secret or
// the token, and the client_id is rendered.
func TestSecretAndTokenRedactedInEveryForm(t *testing.T) {
	srv := newServer(t, &tokenEndpoint{}, func(w http.ResponseWriter, _ *http.Request) {
		jsonAnswer(w, 403, `{"code":"insufficient_scope","error":"Needs usage:read."}`)
	})
	c := newTestClient(t, srv)
	_, callErr := c.GetUsage(context.Background()) // the token lgr_at_1 is now cached
	if callErr == nil {
		t.Fatal("the call succeeded")
	}
	cc := credentials(t, c)
	tok, _ := cc.Token(context.Background())
	secrets := []string{testSecret, tok.ExposeSecret()}

	values := map[string]any{
		"client": c, "credentials": cc, "call error": callErr, "token": tok, "secret": cc.secret,
		"oauth error":       &OAuthError{Status: 401, ErrorCode: "invalid_client"},
		"maintenance error": &MaintenanceError{Body: "down"},
		"transport error":   transportError(context.Background(), errors.New("echo "+testSecret+" "+tok.raw), false, testSecret, tok.raw),
	}
	for name, v := range values {
		assertNoSecret(t, name, v, secrets)
	}
	for name, v := range map[string]any{"client": c, "credentials": cc} {
		assertClientIDShown(t, name, v)
	}
	for _, v := range []any{tok, cc.secret} {
		if raw, _ := json.Marshal(v); string(raw) != `"[REDACTED]"` {
			t.Errorf("%T marshals to %s", v, raw)
		}
	}
	if tok.ExposeSecret() != "lgr_at_1" || cc.secret.ExposeSecret() != testSecret {
		t.Error("ExposeSecret does not return the raw value")
	}
}
