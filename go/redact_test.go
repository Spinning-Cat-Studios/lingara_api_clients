package lingara

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
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

const (
	mintedSecret = "lgr_et_unit0000000000000000000000000000000000000"
	mintAnswer   = `{"token":"` + mintedSecret + `","expires_at":"2026-10-01T09:27:44Z","expires_in":900,` +
		`"subject":"lgr_sub_unit","scopes":["embed:play"],"account_linked":false}`
)

// TestMintedTokenRendersRedacted: 1.10.26w AC15. A MintedToken built from a
// mint's answer, alone and inside its Result, shows [REDACTED] and never the
// lgr_et_ value under %v, %+v, %#v, %s, %q, slog's text and JSON handlers
// and json.Marshal, while Token.ExposeSecret returns it; an answer missing
// subject is refused as Kind MalformedResponse, and that error does not
// carry the token either.
func TestMintedTokenRendersRedacted(t *testing.T) {
	c := newTestClient(t, mintServer(t))
	res, err := c.CreateEmbedToken(context.Background(), EmbedTokenRequest{PlayerRef: "p-1"})
	if err != nil {
		t.Fatal(err)
	}
	minted := res.Value
	for name, v := range map[string]any{"minted token": minted, "pointer": &minted, "result": res} {
		assertNoSecret(t, name, v, []string{mintedSecret})
		assertMarshalsRedacted(t, name, v)
	}
	if minted.Token.ExposeSecret() != mintedSecret || minted.Subject != "lgr_sub_unit" || minted.ExpiresIn != 900*time.Second {
		t.Errorf("the fields were not carried: %#v, %s", minted, minted.Token.ExposeSecret())
	}

	_, err = c.CreateEmbedToken(context.Background(), EmbedTokenRequest{PlayerRef: "p-missing"})
	var te *TransportError
	if !errors.As(err, &te) || te.Kind != MalformedResponse {
		t.Fatalf("an answer missing subject gave %v, want Kind MalformedResponse", err)
	}
	assertNoSecret(t, "malformed mint error", err, []string{mintedSecret})
}

// mintServer answers every mint with mintAnswer, except player p-missing's,
// whose answer has no subject.
func mintServer(t *testing.T) *httptest.Server {
	t.Helper()
	missing := strings.Replace(mintAnswer, `"subject":"lgr_sub_unit",`, "", 1)
	return newServer(t, &tokenEndpoint{}, func(w http.ResponseWriter, r *http.Request) {
		var req EmbedTokenRequest
		_ = json.NewDecoder(r.Body).Decode(&req)
		if req.PlayerRef == "p-missing" {
			jsonAnswer(w, 200, missing)
			return
		}
		jsonAnswer(w, 200, mintAnswer)
	})
}

// assertMarshalsRedacted: json.Marshal of v says [REDACTED] and never the
// minted token.
func assertMarshalsRedacted(t *testing.T, name string, v any) {
	t.Helper()
	raw, err := json.Marshal(v)
	if err != nil || strings.Contains(string(raw), mintedSecret) || !strings.Contains(string(raw), redacted) {
		t.Errorf("%s: json.Marshal gave %s, %v", name, raw, err)
	}
}
