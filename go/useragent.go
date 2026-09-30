package lingara

import (
	"regexp"
	"runtime"
)

// K6: identification (CONTRACT.md K6; ADR 29.9.26q D4).

// releasedToolchain matches a released Go toolchain's runtime.Version(), such
// as go1.23.4. A devel toolchain prints spaces and a date, and an rc or an
// X: experiment build carries letters; those render as "unknown", so the
// value stays visible ASCII with no ")".
var releasedToolchain = regexp.MustCompile(`^go[0-9]+(\.[0-9]+)*$`)

// userAgent is `lingara-go/<Version> (<toolchain>; <GOOS>/<GOARCH>)`, then a
// caller's own product token after one space. The library's token always
// comes first.
func userAgent(suffix string) string {
	own := "lingara-go/" + Version + " (" + toolchain(runtime.Version()) + "; " + runtime.GOOS + "/" + runtime.GOARCH + ")"
	if suffix == "" {
		return own
	}
	return own + " " + suffix
}

func toolchain(version string) string {
	if releasedToolchain.MatchString(version) {
		return version
	}
	return "unknown"
}
