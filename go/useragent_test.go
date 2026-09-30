package lingara

import (
	"context"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"testing"
)

// contractUserAgent is CONTRACT.md K6's pattern, which the conformance
// server checks on every request it replays.
var contractUserAgent = regexp.MustCompile(`^lingara-(typescript|rust|go|java|kotlin|ruby|php)/(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)? \([\x20-\x28\x2A-\x7E]+\)( .+)?$`)

// TestUserAgentShape: 29.9.26q AC21. The User-Agent matches C2 D8's pattern
// with <lang> go on both the token request and a /v1 request, and a suffix
// is appended after it.
func TestUserAgentShape(t *testing.T) {
	var mu sync.Mutex
	seen := map[string]string{}
	record := func(path string, h http.Handler) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			mu.Lock()
			seen[path] = r.Header.Get("User-Agent")
			mu.Unlock()
			h.ServeHTTP(w, r)
		}
	}
	srv := newServer(t, record("token", &tokenEndpoint{}), record("v1", http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		jsonAnswer(w, 200, `{"allowance":[]}`)
	})))
	if _, err := newTestClient(t, srv, WithUserAgentSuffix("kanji-quest/2.1")).GetUsage(context.Background()); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	for _, path := range []string{"token", "v1"} {
		ua := seen[path]
		if !contractUserAgent.MatchString(ua) || !strings.HasPrefix(ua, "lingara-go/"+Version+" (") || !strings.HasSuffix(ua, ") kanji-quest/2.1") {
			t.Errorf("%s request: User-Agent %q", path, ua)
		}
	}
	for version, want := range map[string]string{"go1.23.4": "go1.23.4", "go1.24rc1": "unknown", "devel go1.25-abc Tue": "unknown", "X:boringcrypto": "unknown"} {
		if got := toolchain(version); got != want {
			t.Errorf("toolchain(%q) = %q, want %q", version, got, want)
		}
	}
}
