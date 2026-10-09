package lingara

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"
)

// Embedding Lingara (ADR 1.10.26w): the mint, the player delete and the NPC
// turn. The operations themselves are thin, as in operations.go; what is
// hand-written here is what a generator cannot know, that a minted token is
// a credential (D3).

// MintedToken is a player's embed token, as CreateEmbedToken returns it. Its
// Token renders as [REDACTED] (CONTRACT.md K1); Token.ExposeSecret is the one
// way to read it. MintedToken formats itself rather than trusting fmt with
// its fields, so every fmt verb, slog and json.Marshal redact it.
type MintedToken struct {
	// Token is the lgr_et_ bearer token to hand to the player's device.
	Token Token
	// ExpiresAt is when the token stops working. Lingara never refreshes
	// it: mint again.
	ExpiresAt time.Time
	// ExpiresIn is the token's lifetime counted from the answer. Prefer it
	// on a device whose clock cannot be trusted.
	ExpiresIn time.Duration
	// Subject is the player's pairwise lgr_sub_, stable across mints. Store
	// it beside the player: it is how an event names them.
	Subject string
	// Scopes are the scopes granted, every handable scope the client holds
	// when the request named none.
	Scopes []string
	// AccountLinked says whether the player has linked a Lingara account.
	AccountLinked bool
}

// embedTokenFields is every field the mint's answer must carry (ADR 1.10.26w
// D3). Go zero-fills a missing field, so presence is checked by name.
var embedTokenFields = []string{"token", "expires_at", "expires_in", "subject", "scopes", "account_linked"}

// newMintedToken builds a MintedToken from the mint's raw answer. Each of the
// six fields must be present, non-null and of its JSON type, and the token
// must start lgr_et_. A failure is Kind MalformedResponse and carries none
// of the body, so the token cannot leak through a cause.
func newMintedToken(raw []byte) (MintedToken, error) {
	var present map[string]json.RawMessage
	var answer EmbedToken
	if json.Unmarshal(raw, &present) != nil || json.Unmarshal(raw, &answer) != nil {
		return MintedToken{}, malformedMint("a field is not of its JSON type")
	}
	for _, name := range embedTokenFields {
		if value, ok := present[name]; !ok || string(value) == "null" {
			return MintedToken{}, malformedMint("the answer has no " + name)
		}
	}
	if !strings.HasPrefix(answer.Token, "lgr_et_") {
		return MintedToken{}, malformedMint("the token is not an lgr_et_ token")
	}
	return MintedToken{
		Token: NewToken(answer.Token), ExpiresAt: answer.ExpiresAt, ExpiresIn: time.Duration(answer.ExpiresIn) * time.Second,
		Subject: answer.Subject, Scopes: answer.Scopes, AccountLinked: answer.AccountLinked,
	}, nil
}

func malformedMint(reason string) error {
	return &TransportError{Kind: MalformedResponse, Err: errors.New("lingara: createEmbedToken: " + reason)}
}

// CreateEmbedToken mints a token for one player (scope embed:mint, and only
// for a metered client). Call it on your server, never on the player's
// device, and mint again when the token expires: Lingara never refreshes
// one. A 403 insufficient_scope or embed_needs_metered is the server's
// answer, returned as an *APIError. K4's Retry-After loop applies; two mints
// for one player are harmless.
func (c *Client) CreateEmbedToken(ctx context.Context, body EmbedTokenRequest) (*Result[MintedToken], error) {
	payload, err := json.Marshal(body)
	if err != nil {
		return nil, fmt.Errorf("lingara: createEmbedToken: encoding the request body: %w", err)
	}
	rt := routes["createEmbedToken"]
	res, err := sendJSON[json.RawMessage](ctx, c, request{
		method: rt.method, url: c.url(rt.path, ""), body: payload, accept: "application/json", needsToken: rt.needsToken,
	})
	if err != nil {
		return nil, err
	}
	minted, err := newMintedToken(res.Value)
	if err != nil {
		return nil, err
	}
	return &Result[MintedToken]{Value: minted, ServedVersion: res.ServedVersion}, nil
}

// DeleteEmbedPlayer deletes a player and revokes their tokens (scope
// embed:mint). playerRef is sent as one percent-encoded path segment. An
// unknown player is still a success, so the call is idempotent and K4's
// retries apply; it keeps working while embedding is switched off for your
// client. The answer has no body: the Result carries only ServedVersion.
func (c *Client) DeleteEmbedPlayer(ctx context.Context, playerRef string) (*Result[struct{}], error) {
	rt := routes["deleteEmbedPlayer"]
	return sendNoContent(ctx, c, request{method: rt.method, url: c.url(rt.path, playerRef), accept: "application/json", needsToken: rt.needsToken})
}

// SendDialogueTurn streams an NPC's reply to one line (scope embed:play,
// from an embed token or a metered client's own token). It yields delta and
// notice events and ends on done.
//
// The window is yours to keep: at most 12 history entries, with line and
// each entry at most 500 characters, and no total cap. Send each NPC reply
// back into history cut to its first 500 characters. The library neither
// checks nor trims it.
//
// It is never retried: each attempt spends the player's NPC cells and your
// metered cells, so a 429 or 503 is returned at once as an *APIError with
// its RetryAfter, and you decide whether to send the turn again. Two
// refusals no retry helps: 403 embed_needs_metered, and 422
// safety_input_flagged, which means say something else.
func (c *Client) SendDialogueTurn(ctx context.Context, body DialogueTurnRequest) (*Stream[SendDialogueTurnEvent], error) {
	return openStream(ctx, c, streamCall{operationID: "sendDialogueTurn", body: body, single: true}, decodeSendDialogueTurnEvent)
}

// mintedTokenJSON is a MintedToken's JSON form: the wire's names, the token
// redacted by Token's own MarshalJSON.
type mintedTokenJSON struct {
	Token         Token     `json:"token"`
	ExpiresAt     time.Time `json:"expires_at"`
	ExpiresIn     int64     `json:"expires_in"`
	Subject       string    `json:"subject"`
	Scopes        []string  `json:"scopes"`
	AccountLinked bool      `json:"account_linked"`
}

func (m MintedToken) String() string                { return m.render().plain }
func (m MintedToken) GoString() string              { return m.render().detailed }
func (m MintedToken) Format(f fmt.State, verb rune) { writeFormatted(f, verb, m.render()) }

// MarshalJSON renders the wire's six fields with the token redacted.
func (m MintedToken) MarshalJSON() ([]byte, error) {
	return json.Marshal(mintedTokenJSON{
		Token: m.Token, ExpiresAt: m.ExpiresAt, ExpiresIn: int64(m.ExpiresIn / time.Second),
		Subject: m.Subject, Scopes: m.Scopes, AccountLinked: m.AccountLinked,
	})
}

// LogValue is a group with the token redacted.
func (m MintedToken) LogValue() slog.Value {
	return slog.GroupValue(
		slog.String("token", redacted),
		slog.Time("expires_at", m.ExpiresAt),
		slog.Duration("expires_in", m.ExpiresIn),
		slog.String("subject", m.Subject),
		slog.Any("scopes", m.Scopes),
		slog.Bool("account_linked", m.AccountLinked),
	)
}

func (m MintedToken) render() rendering {
	at := m.ExpiresAt.Format(time.RFC3339)
	detailed := fmt.Sprintf("lingara.MintedToken{Token:%s, ExpiresAt:%q, ExpiresIn:%s, Subject:%q, Scopes:%q, AccountLinked:%t}",
		redacted, at, m.ExpiresIn, m.Subject, m.Scopes, m.AccountLinked)
	return rendering{plain: fmt.Sprintf("lingara.MintedToken(subject=%s, expires_at=%s)", m.Subject, at), detailed: detailed}
}
