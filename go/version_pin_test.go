package lingara

import (
	"bytes"
	"context"
	"log/slog"
	"net/http"
	"strings"
	"testing"
	"time"
)

// captureWarnings points slog's default logger at a buffer for one test.
func captureWarnings(t *testing.T) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	previous := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelWarn})))
	t.Cleanup(func() { slog.SetDefault(previous) })
	return &buf
}

// deprecatedServer answers /v1/usage under whichever version the request
// names, deprecated, and returns a builder of clients pointed at it.
func deprecatedServer(t *testing.T, deprecation string) func(...Option) *Client {
	t.Helper()
	srv := newServer(t, &tokenEndpoint{}, func(w http.ResponseWriter, r *http.Request) {
		id := r.Header.Get("Lingara-Version")
		w.Header().Set("Lingara-Version", id)
		w.Header().Set("Deprecation", deprecation)
		w.Header().Set("Sunset", "Mon, 01 Mar 2027 00:00:00 GMT")
		w.Header().Set("Link", `</v1/versions/`+id+`>; rel="deprecation"; type="application/json"`)
		jsonAnswer(w, 200, `{"allowance":[]}`)
	})
	return func(opts ...Option) *Client { return newTestClient(t, srv, opts...) }
}

// TestDeprecationHookParsingAndWarnOnce: 29.9.26q AC25. A Deprecation header
// calls the hook once with parsed times and a resolved Link; an unparseable
// one leaves the field nil; a panicking hook does not fail the call; with no
// hook one warning is logged per version id.
func TestDeprecationHookParsingAndWarnOnce(t *testing.T) {
	n := hookNotice(t, "@1790812800")
	link := ""
	if n.Link != nil && n.Link.URL != nil {
		link = n.Link.URL.String()
	}
	if n.Version != "2026-09-affable-cat" || !sameInstant(n.DeprecatedAt, time.Unix(1790812800, 0)) ||
		!sameInstant(n.SunsetAt, time.Date(2027, 3, 1, 0, 0, 0, 0, time.UTC)) || !strings.HasSuffix(link, "/v1/versions/2026-09-affable-cat") {
		t.Fatalf("the notice was %+v", n)
	}
	if n = hookNotice(t, "Tue, 01 Sep 2026 00:00:00 GMT"); n.DeprecatedAt != nil || n.RawDeprecation != "Tue, 01 Sep 2026 00:00:00 GMT" {
		t.Fatalf("an unparseable Deprecation gave %+v, want a nil DeprecatedAt and the raw header", n)
	}
	build := deprecatedServer(t, "@1790812800")
	panicking := build(WithDeprecationHook(func(DeprecationNotice) { panic("the hook broke") }))
	if _, err := panicking.GetUsage(context.Background()); err != nil {
		t.Fatalf("a panicking hook failed the call: %v", err)
	}
	warnOncePerID(t, build)
}

// hookNotice is the one notice a recording hook receives for a response
// deprecated with this Deprecation header, under 2026-09-affable-cat.
func hookNotice(t *testing.T, deprecation string) DeprecationNotice {
	t.Helper()
	var notices []DeprecationNotice
	hooked := deprecatedServer(t, deprecation)(WithVersion("2026-09-affable-cat"), WithDeprecationHook(func(n DeprecationNotice) { notices = append(notices, n) }))
	if _, err := hooked.GetUsage(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(notices) != 1 {
		t.Fatalf("the hook was called %d times, want 1", len(notices))
	}
	return notices[0]
}

func sameInstant(got *time.Time, want time.Time) bool { return got != nil && got.Equal(want) }

// warnOncePerID: with no hook, each client logs one warning per deprecated
// version id.
func warnOncePerID(t *testing.T, build func(...Option) *Client) {
	t.Helper()
	ctx := context.Background()
	warnings := captureWarnings(t)
	for _, id := range []string{"2026-09-affable-cat", "2026-09-affable-cat", "2026-09-brave-otter"} {
		if _, err := build(WithVersion(id)).GetUsage(ctx); err != nil {
			t.Fatal(err)
		}
	}
	unhooked := build(WithVersion("2026-09-affable-cat"))
	_, _ = unhooked.GetUsage(ctx)
	_, _ = unhooked.GetUsage(ctx)
	if got := strings.Count(warnings.String(), "2026-09-affable-cat is deprecated"); got != 3 {
		// Three clients warned about it once each; the one called twice did
		// not warn twice.
		t.Fatalf("%d deprecation warnings for one id across three clients, want 3:\n%s", got, warnings)
	}
	if got := strings.Count(warnings.String(), "2026-09-brave-otter is deprecated"); got != 1 {
		t.Fatalf("%d deprecation warnings for a second id, want 1", got)
	}
}
