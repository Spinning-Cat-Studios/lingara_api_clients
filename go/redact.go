package lingara

import (
	"fmt"
	"io"
	"log/slog"
)

// Redaction (CONTRACT.md K1; ADR 29.9.26q D5). The client secret and every
// access token render as [REDACTED] in every fmt verb, in slog and in JSON;
// ExposeSecret is the one accessor that does not.
//
// Redacting the two secret types is not enough in Go. fmt cannot call a
// method on an unexported struct field, because it cannot take the field's
// interface value, so %+v of a struct holding a secret in an unexported field
// would print it raw. Every type that holds one therefore formats itself:
// *Client, *ClientCredentials and each of the four error types.

const redacted = "[REDACTED]"

// ClientSecret is an OAuth client secret. Build one with NewClientSecret.
type ClientSecret struct{ raw string }

// NewClientSecret wraps a raw client secret.
func NewClientSecret(raw string) ClientSecret { return ClientSecret{raw} }

// ExposeSecret returns the raw secret: the one accessor that does not redact.
func (s ClientSecret) ExposeSecret() string { return s.raw }

func (ClientSecret) String() string                { return redacted }
func (ClientSecret) GoString() string              { return redacted }
func (ClientSecret) Format(f fmt.State, verb rune) { writeRedacted(f, verb) }
func (ClientSecret) LogValue() slog.Value          { return slog.StringValue(redacted) }
func (ClientSecret) MarshalJSON() ([]byte, error)  { return []byte(`"` + redacted + `"`), nil }
func (Token) String() string                       { return redacted }
func (Token) GoString() string                     { return redacted }
func (Token) Format(f fmt.State, verb rune)        { writeRedacted(f, verb) }
func (Token) LogValue() slog.Value                 { return slog.StringValue(redacted) }
func (Token) MarshalJSON() ([]byte, error)         { return []byte(`"` + redacted + `"`), nil }
func (c *Client) Format(f fmt.State, verb rune)    { writeFormatted(f, verb, c.render()) }
func (c *Client) LogValue() slog.Value             { return slog.GroupValue(c.attrs()...) }
func (c *ClientCredentials) Format(f fmt.State, verb rune) {
	writeFormatted(f, verb, c.render())
}
func (c *ClientCredentials) LogValue() slog.Value { return slog.GroupValue(c.attrs()...) }

func writeRedacted(f fmt.State, verb rune) {
	if verb == 'q' {
		_, _ = fmt.Fprintf(f, "%q", redacted)
		return
	}
	_, _ = io.WriteString(f, redacted)
}

// rendering is what a self-formatting type shows: a one-line summary for %v
// and %s, and the detailed form for %+v and %#v.
type rendering struct{ plain, detailed string }

func writeFormatted(f fmt.State, verb rune, r rendering) {
	switch {
	case verb == 'q':
		_, _ = fmt.Fprintf(f, "%q", r.plain)
	case verb == 'v' && (f.Flag('+') || f.Flag('#')):
		_, _ = io.WriteString(f, r.detailed)
	default:
		_, _ = io.WriteString(f, r.plain)
	}
}

func (c *Client) render() rendering {
	detailed := fmt.Sprintf("&lingara.Client{BaseURL:%q, Version:%q, ClientID:%q, ClientSecret:%s, Token:%s}",
		c.baseURL, c.version, c.clientID(), redacted, redacted)
	return rendering{plain: fmt.Sprintf("lingara.Client(%s, client_id=%s)", c.baseURL, c.clientID()), detailed: detailed}
}

func (c *Client) attrs() []slog.Attr {
	return []slog.Attr{
		slog.String("base_url", c.baseURL),
		slog.String("client_id", c.clientID()),
		slog.String("client_secret", redacted),
	}
}

// clientID is the credentials' client id, or "" for a credential-free client
// or a caller's own token source.
func (c *Client) clientID() string {
	if cc, ok := c.tokens.(*ClientCredentials); ok {
		return cc.clientID
	}
	return ""
}

func (c *ClientCredentials) render() rendering {
	detailed := fmt.Sprintf("&lingara.ClientCredentials{ClientID:%q, ClientSecret:%s, TokenURL:%q, Token:%s}",
		c.clientID, redacted, c.cfg.tokenURL, redacted)
	return rendering{plain: fmt.Sprintf("lingara.ClientCredentials(client_id=%s)", c.clientID), detailed: detailed}
}

func (c *ClientCredentials) attrs() []slog.Attr {
	return []slog.Attr{slog.String("client_id", c.clientID), slog.String("client_secret", redacted)}
}
