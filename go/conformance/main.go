// Command conformance is the Go library's conformance harness
// (conformance/README.md, Writing a harness; ADR 29.9.26q D7). It runs every
// case through the library's public API: each client is built from the
// case's client block through the public options only (rig.go), a
// `parallel: n` step is n goroutines that all start before any completes,
// and `cancel_after_events: n` cancels the call's context after its n-th
// event (observe.go).
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

type env struct {
	base, token, control, out string
	only                      map[string]bool
}

func main() {
	passed, err := run()
	if err != nil {
		fmt.Fprintln(os.Stderr, "✗ harness:", err)
		os.Exit(1)
	}
	if !passed {
		os.Exit(1)
	}
}

func readEnv() (env, error) {
	var e env
	for name, dst := range map[string]*string{
		"LINGARA_CONFORMANCE_BASE_URL":    &e.base,
		"LINGARA_CONFORMANCE_TOKEN_URL":   &e.token,
		"LINGARA_CONFORMANCE_CONTROL_URL": &e.control,
		"LINGARA_CONFORMANCE_OUT":         &e.out,
	} {
		if *dst = os.Getenv(name); *dst == "" {
			return e, fmt.Errorf("%s is not set: run this through conformance-server run", name)
		}
	}
	if only := os.Getenv("LINGARA_CONFORMANCE_ONLY"); only != "" {
		e.only = map[string]bool{}
		for _, id := range strings.Split(only, ",") {
			e.only[strings.TrimSpace(id)] = true
		}
	}
	return e, nil
}

// control calls the conformance server's control surface and decodes its
// JSON answer.
func control(method, url string, into any) error {
	req, err := http.NewRequest(method, url, nil)
	if err != nil {
		return err
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("%s: %w", url, err)
	}
	defer res.Body.Close()
	body, err := io.ReadAll(res.Body)
	if err != nil {
		return fmt.Errorf("%s: %w", url, err)
	}
	if res.StatusCode < 200 || res.StatusCode > 299 {
		return fmt.Errorf("%s: %s %s", url, res.Status, body)
	}
	return json.Unmarshal(body, into)
}

func run() (bool, error) {
	e, err := readEnv()
	if err != nil {
		return false, err
	}
	var ids []string
	if err := control(http.MethodGet, e.control+"/cases", &ids); err != nil {
		return false, err
	}
	allPassed := true
	for _, id := range ids {
		if e.only != nil && !e.only[id] {
			continue
		}
		passed, err := runCase(e, id)
		if err != nil {
			return false, err
		}
		allPassed = allPassed && passed
	}
	return allPassed, nil
}

func runCase(e env, id string) (bool, error) {
	started := time.Now()
	var c map[string]any
	if err := control(http.MethodGet, e.control+"/cases/"+id, &c); err != nil {
		return false, err
	}
	var armed any
	if err := control(http.MethodPost, e.control+"/cases/"+id+"/arm", &armed); err != nil {
		return false, err
	}
	clientMismatches, err := steps(e, c)
	if err != nil {
		clientMismatches = []string{"harness: " + err.Error()}
	}
	var verdict struct {
		Mismatches []any `json:"mismatches"`
	}
	if err := control(http.MethodPost, e.control+"/cases/"+id+"/finish", &verdict); err != nil {
		return false, err
	}
	return report(e, id, started, clientMismatches, verdict.Mismatches)
}

// report appends the case's result line and says whether it passed.
func report(e env, id string, started time.Time, client []string, server []any) (bool, error) {
	pass := len(client) == 0 && len(server) == 0
	result := map[bool]string{true: "pass", false: "fail"}[pass]
	if client == nil {
		client = []string{}
	}
	if server == nil {
		server = []any{}
	}
	line, err := json.Marshal(map[string]any{
		"case": id, "lang": "go", "library_version": lingara.Version, "result": result,
		"client_mismatches": client, "server_mismatches": server, "duration_ms": time.Since(started).Milliseconds(),
	})
	if err != nil {
		return false, err
	}
	out, err := os.OpenFile(e.out, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return false, err
	}
	defer out.Close()
	if _, err := fmt.Fprintf(out, "%s\n", line); err != nil {
		return false, err
	}
	if !pass {
		fmt.Fprintf(os.Stderr, "✗ %s: client %v server %v\n", id, client, server)
	}
	return pass, nil
}

func steps(e env, c map[string]any) ([]string, error) {
	base, token, err := urls(e, c)
	if err != nil {
		return nil, err
	}
	clientBlock, _ := c["client"].(map[string]any)
	r, err := build(clientBlock, base, token)
	if err != nil {
		return nil, err
	}
	stepList, _ := c["steps"].([]any)
	var mismatches []string
	for _, raw := range stepList {
		st, _ := raw.(map[string]any)
		if seconds, ok := st["advance_clock_s"].(float64); ok {
			r.advance(int64(seconds))
		}
		expect, hasExpect := st["expect"].(map[string]any)
		if !hasExpect {
			continue
		}
		expect = substitute(expect, e.base).(map[string]any)
		if call, ok := st["call"].(map[string]any); ok {
			mismatches = append(mismatches, runStep(r, call, expect)...)
		}
		for _, kind := range []string{"events", "tail"} {
			if helper, ok := st[kind].(map[string]any); ok {
				mismatches = append(mismatches, runHelper(r, kind, helper, expect)...)
			}
		}
	}
	return mismatches, nil
}

// urls is where the client points: the case server, or, for
// `base_url: unreachable`, a port bound and released so nothing listens.
func urls(e env, c map[string]any) (string, string, error) {
	clientBlock, _ := c["client"].(map[string]any)
	if clientBlock["base_url"] != "unreachable" {
		return e.base, e.token, nil
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", "", err
	}
	base := "http://" + l.Addr().String()
	if err := l.Close(); err != nil {
		return "", "", errors.Join(errors.New("releasing the unreachable port"), err)
	}
	return base, base + "/oauth/token", nil
}
