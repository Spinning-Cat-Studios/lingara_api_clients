package main

import (
	"bytes"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// TestFixtureViewYieldsUnionsRoutesAndVersion: 29.9.26q AC27. Over a
// fixture view, go/codegen writes one sealed interface and one decoder per
// stream, nine routes with the streams' event names, and the VERSION
// constant, and two runs are byte-identical.
func TestFixtureViewYieldsUnionsRoutesAndVersion(t *testing.T) {
	version := filepath.Join(t.TempDir(), "VERSION")
	if err := os.WriteFile(version, []byte("1.2.3-rc.1\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	first, err := generate("testdata/view.json", version)
	if err != nil {
		t.Fatal(err)
	}
	second, _ := generate("testdata/view.json", version)
	for name, src := range first {
		if !bytes.Equal(src, second[name]) {
			t.Errorf("%s differs between two runs", name)
		}
	}
	checkStreams(t, flat(first["streams_gen.go"]))
	checkRoutes(t, flat(first["routes_gen.go"]))
	versionSrc := string(first["version_gen.go"])
	if !strings.Contains(versionSrc, `const Version = "1.2.3-rc.1"`) || !strings.Contains(versionSrc, `const GeneratedForVersion = "2026-09-fixture-view"`) {
		t.Errorf("version_gen.go is\n%s", versionSrc)
	}
}

// flat collapses whitespace: gofmt aligns columns, so every comparison is
// over single spaces.
func flat(src []byte) string { return strings.Join(strings.Fields(string(src)), " ") }

func checkStreams(t *testing.T, streams string) {
	t.Helper()
	for _, union := range []string{"StreamWordsEvent", "FollowPlanEvent"} {
		if strings.Count(streams, "type "+union+" interface{ is"+union+"() }") != 1 {
			t.Errorf("no single sealed interface %s", union)
		}
		if strings.Count(streams, "func decode"+union+"(name string, frame []byte) ("+union+", bool, error)") != 1 {
			t.Errorf("no single decoder for %s", union)
		}
	}
	if !strings.Contains(streams, "func (StreamWordsEventWord) isStreamWordsEvent() {}") {
		t.Error("the word branch has no marker method")
	}
}

func checkRoutes(t *testing.T, routes string) {
	t.Helper()
	if got := len(regexp.MustCompile(`"[a-zA-Z]+": \{method:`).FindAllString(routes, -1)); got != 9 {
		t.Errorf("%d routes, want nine:\n%s", got, routes)
	}
	for _, want := range []string{
		`"streamWords": {method: "POST", path: "/v1/words/stream", needsToken: true`,
		`events: []string{"started", "word", "done", "error"}, ends: map[string]ending{"done": endQuiet, "error": endRaise}`,
		`events: []string{"phase", "result", "pending", "error"}, ends: map[string]ending{"result": endYield, "pending": endYield, "error": endRaise}`,
		`"getC": {method: "GET", path: "/v1/c", needsToken: false}`,
		`"deleteF": {method: "DELETE", path: "/v1/f/{id}", needsToken: true}`,
	} {
		if !strings.Contains(routes, want) {
			t.Errorf("routes_gen.go lacks %s", want)
		}
	}
}
