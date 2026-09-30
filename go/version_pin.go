package lingara

import (
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// K2: the served version and the deprecation hook (CONTRACT.md K2; ADR
// 29.9.26q D4). The pin itself is one header the client adds; this reads what
// came back.

// DeprecationNotice is what a response under a deprecated version says about
// it. An unparseable header leaves its parsed field nil, never an error.
type DeprecationNotice struct {
	// Version is the Lingara-Version echo, or "".
	Version string
	// DeprecatedAt is Deprecation, parsed from @<unix seconds>.
	DeprecatedAt *time.Time
	// SunsetAt is Sunset, parsed from an IMF-fixdate.
	SunsetAt *time.Time
	Link     *DeprecationLink
	// RawDeprecation and RawSunset are the headers as sent.
	RawDeprecation string
	RawSunset      string
}

// DeprecationLink is a Link header: its raw value, and its target resolved
// against the request URL (RFC 8288 §3.2), or nil.
type DeprecationLink struct {
	Raw string
	URL *url.URL
}

// versionObserver, per client, reads the served version off each response
// and reports a deprecation once per response to the hook or, with no hook,
// warns once per version id. Separately, it warns once per served id that is
// not the version the models were generated from (ADR 30.9.26a §4).
type versionObserver struct {
	hook func(DeprecationNotice)

	mu     sync.Mutex
	warned map[string]bool
	// Its own set: sharing warned would let a version that is both
	// deprecated and mismatched warn only once in total.
	mismatched map[string]bool
}

func newVersionObserver(hook func(DeprecationNotice)) *versionObserver {
	return &versionObserver{hook: hook, warned: map[string]bool{}, mismatched: map[string]bool{}}
}

// observe returns the Lingara-Version echo, after reporting any deprecation
// or mismatch.
func (o *versionObserver) observe(h http.Header, requestURL *url.URL) string {
	if notice, ok := deprecationNotice(h, requestURL); ok {
		o.report(notice)
	}
	served := h.Get("Lingara-Version")
	if served != "" {
		o.checkGenerated(served)
	}
	return served
}

// checkGenerated warns once per served id that is not GeneratedForVersion,
// and says whether it did.
func (o *versionObserver) checkGenerated(served string) bool {
	if served == GeneratedForVersion || !o.firstTime(o.mismatched, served) {
		return false
	}
	slog.Warn("Lingara API version " + served + " served this response, but this library's models were generated for " +
		GeneratedForVersion + "; response shapes may differ. Pin the OAuth client to " + GeneratedForVersion + " or upgrade the library.")
	return true
}

// report hands the notice to the hook, or warns once per version id with no
// hook. A hook that panics never fails the call.
func (o *versionObserver) report(n DeprecationNotice) {
	if o.hook == nil {
		o.warnOnce(n)
		return
	}
	defer func() {
		if r := recover(); r != nil {
			slog.Debug("the Lingara deprecation hook panicked; the call continues", "panic", r)
		}
	}()
	o.hook(n)
}

func (o *versionObserver) warnOnce(n DeprecationNotice) {
	// An absent echo counts as one id: the empty string.
	if !o.firstTime(o.warned, n.Version) {
		return
	}
	name := n.Version
	if name == "" {
		name = "(unnamed)"
	}
	sunset := ""
	if n.RawSunset != "" {
		sunset = "; sunset " + n.RawSunset
	}
	slog.Warn("Lingara API version " + name + " is deprecated" + sunset + ". See GET /v1/versions.")
}

func (o *versionObserver) firstTime(seen map[string]bool, id string) bool {
	o.mu.Lock()
	defer o.mu.Unlock()
	if seen[id] {
		return false
	}
	seen[id] = true
	return true
}

// deprecationNotice is the notice for a response, or false when it carries
// no Deprecation header.
func deprecationNotice(h http.Header, requestURL *url.URL) (DeprecationNotice, bool) {
	raw := h.Get("Deprecation")
	if raw == "" {
		return DeprecationNotice{}, false
	}
	n := DeprecationNotice{
		Version:        h.Get("Lingara-Version"),
		DeprecatedAt:   parseDeprecation(raw),
		RawDeprecation: raw,
		RawSunset:      h.Get("Sunset"),
	}
	n.SunsetAt = parseSunset(n.RawSunset)
	if link := h.Get("Link"); link != "" {
		n.Link = parseLink(link, requestURL)
	}
	return n, true
}

func parseDeprecation(value string) *time.Time {
	digits, ok := strings.CutPrefix(strings.TrimSpace(value), "@")
	if !ok {
		return nil
	}
	seconds, err := strconv.ParseInt(digits, 10, 64)
	if err != nil {
		return nil
	}
	t := time.Unix(seconds, 0).UTC()
	return &t
}

// parseSunset reads an IMF-fixdate only (Sun, 06 Nov 1994 08:49:37 GMT):
// http.ParseTime also reads the two obsolete formats, which Sunset does not
// allow.
func parseSunset(value string) *time.Time {
	t, err := time.Parse(http.TimeFormat, strings.TrimSpace(value))
	if err != nil {
		return nil
	}
	return &t
}

func parseLink(raw string, requestURL *url.URL) *DeprecationLink {
	link := &DeprecationLink{Raw: raw}
	rest, ok := strings.CutPrefix(strings.TrimSpace(raw), "<")
	if !ok {
		return link
	}
	target, _, ok := strings.Cut(rest, ">")
	if !ok {
		return link
	}
	if u, err := requestURL.Parse(target); err == nil {
		link.URL = u
	}
	return link
}
