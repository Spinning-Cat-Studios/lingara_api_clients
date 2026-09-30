package lingara

import (
	"context"
	"go/parser"
	"go/token"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"strings"
	"sync"
	"testing"
)

// TestModuleHasNoRequirements: 29.9.26q AC1. go.mod says go 1.23 and has no
// toolchain and no require line, there is no go.sum, and every file of the
// module, the generated ones included, imports the standard library only.
func TestModuleHasNoRequirements(t *testing.T) {
	mod, err := os.ReadFile("go.mod")
	if err != nil {
		t.Fatal(err)
	}
	if !regexp.MustCompile(`(?m)^go 1\.23$`).Match(mod) {
		t.Errorf("go.mod has no `go 1.23` line:\n%s", mod)
	}
	if regexp.MustCompile(`(?m)^\s*(toolchain|require)\b`).Match(mod) {
		t.Errorf("go.mod names a toolchain or a requirement:\n%s", mod)
	}
	if _, err := os.Stat("go.sum"); err == nil {
		t.Error("go.sum exists, so something is required")
	}
	const self = "github.com/Spinning-Cat-Studios/lingara_api_clients/go/"
	files, _ := filepath.Glob("*.go")
	internal, _ := filepath.Glob("internal/*/*.go")
	for _, path := range append(files, internal...) {
		f, err := parser.ParseFile(token.NewFileSet(), path, nil, parser.ImportsOnly)
		if err != nil {
			t.Fatal(err)
		}
		for _, imp := range f.Imports {
			p := strings.Trim(imp.Path.Value, `"`)
			// A standard-library path has no dot in its first element.
			if first, _, _ := strings.Cut(p, "/"); strings.Contains(first, ".") && !strings.HasPrefix(p, self) {
				t.Errorf("%s imports %s, which is not the standard library", path, p)
			}
		}
	}
}

// TestServedVersionAndCredentialFreeClient: 29.9.26q AC22. A JSON method's
// Result carries ServedVersion from the echo, and a credential-free client
// calls a public operation with no exchange and no Authorization header.
func TestServedVersionAndCredentialFreeClient(t *testing.T) {
	tokens := &tokenEndpoint{}
	var mu sync.Mutex
	var authorization []string
	srv := newServer(t, tokens, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		authorization = append(authorization, r.Header.Get("Authorization"))
		mu.Unlock()
		w.Header().Set("Lingara-Version", GeneratedForVersion)
		jsonAnswer(w, 200, `{"current":"2026-09-glowing-hoatzin","versions":[]}`)
	})
	res, err := newTestClient(t, srv).GetUsage(context.Background())
	if err != nil || res.ServedVersion != GeneratedForVersion {
		t.Fatalf("got %+v, %v; want the served version echoed", res, err)
	}

	mu.Lock()
	authorization = nil
	mu.Unlock()
	free, err := New(WithBaseURL(srv.URL), WithTokenURL(srv.URL+"/oauth/token"))
	if err != nil {
		t.Fatal(err)
	}
	before := tokens.calls.Load()
	versions, err := free.ListAPIVersions(context.Background())
	if err != nil || versions.Value.Current == nil || *versions.Value.Current != "2026-09-glowing-hoatzin" {
		t.Fatalf("got %+v, %v", versions, err)
	}
	mu.Lock()
	defer mu.Unlock()
	if tokens.calls.Load() != before || len(authorization) != 1 || authorization[0] != "" {
		t.Fatalf("a credential-free call made %d exchanges and sent Authorization %q", tokens.calls.Load()-before, authorization)
	}
}

// goName is D3's naming rule: the operationId with its first letter
// upper-cased and Go's initialisms applied.
func goName(operationID string) string {
	name := strings.ToUpper(operationID[:1]) + operationID[1:]
	return strings.NewReplacer("Api", "API", "Id", "ID").Replace(name)
}

// TestOperationMethodsMatchGeneratedRoutes: 29.9.26q AC23. The nine client
// methods and routes_gen.go's keys name the same operations under D3's
// naming rule, both ways.
func TestOperationMethodsMatchGeneratedRoutes(t *testing.T) {
	notOperations := map[string]bool{"Format": true, "LogValue": true}
	methods := map[string]bool{}
	clientType := reflect.TypeOf(&Client{})
	for i := range clientType.NumMethod() {
		if name := clientType.Method(i).Name; !notOperations[name] {
			methods[name] = true
		}
	}
	for id := range routes {
		if !methods[goName(id)] {
			t.Errorf("routes has %s, but *Client has no %s", id, goName(id))
		}
	}
	names := map[string]bool{}
	for id := range routes {
		names[goName(id)] = true
	}
	for name := range methods {
		if !names[name] {
			t.Errorf("*Client has %s, which is no operation in routes", name)
		}
	}
	if len(routes) != 9 || len(methods) != 9 {
		t.Errorf("%d routes and %d methods, want nine of each", len(routes), len(methods))
	}
}

type fixedTokens struct{}

func (fixedTokens) Token(context.Context) (Token, error) { return NewToken("lgr_at_fixed"), nil }
func (fixedTokens) Invalidate(Token)                     {}

// TestNewRefusesConflictingOptions: 29.9.26q AC24. New returns an error for
// WithVersion("") and for WithTokenSource beside WithClientCredentials.
func TestNewRefusesConflictingOptions(t *testing.T) {
	for name, opts := range map[string][]Option{
		"an empty version":                  {WithVersion("")},
		"a token source beside credentials": {WithTokenSource(fixedTokens{}), WithClientCredentials(testClientID, testSecret)},
	} {
		if c, err := New(opts...); err == nil || c != nil {
			t.Errorf("%s: New returned %v, %v; want an error", name, c, err)
		}
	}
	if _, err := New(WithTokenSource(fixedTokens{}), WithVersion("2026-09-affable-cat")); err != nil {
		t.Errorf("a caller's token source alone was refused: %v", err)
	}
	if _, err := New(); err != nil {
		t.Errorf("a credential-free client was refused: %v", err)
	}
}
