# Release (ADR 29.9.26v D2). Ships with the repository.
#
# `languages.toml` is what a release publishes; tools/release-manifest reads
# it. The tree checks run from make/quality.mk's check-publishable, and the
# two publish checks from make/private/quality.mk. RELEASE_MANIFEST is read
# in those recipes, which expand when they run, so this file may be read
# after them.

RELEASE_MANIFEST := $(CARGO) run -q -p release-manifest --

.PHONY: bump-version release-matrix release-build release-conformance test-release-manifest help-release

## Write V to VERSION, every version file and every `since = "next"`, then
## regenerate the version constants each language's codegen writes, so
## check-codegen stays green. Needs every shipped language's codegen toolchain.
bump-version:
	@test -n "$(V)" || { echo "Usage: make bump-version V=X.Y.Z[-pre.N]" >&2; exit 2; }
	$(RELEASE_MANIFEST) bump $(V)
	$(MAKE) codegen

## The GitHub Actions matrix release.yml fans out over, for TAG.
release-matrix:
	@test -n "$(TAG)" || { echo "Usage: make release-matrix TAG=vX.Y.Z[-pre.N]" >&2; exit 2; }
	@$(RELEASE_MANIFEST) matrix $(TAG)

## One language's release build, ID being its languages.toml id: the child's
## own targets, so the release build is the local build (D4). release.yml
## calls these rather than `make check-codegen-$ID`, because the leak scan
## reads every word after a workflow's `make` as a target it must find
## defined, and a target spelt from a variable is none.
release-build:
	@test -n "$(ID)" || { echo "Usage: make release-build ID=<language id>" >&2; exit 2; }
	$(MAKE) check-codegen-$(ID) test-$(ID)

## C2's conformance suite for ID; pass CONFORMANCE_SERVER to reuse a built
## server, which the sub-make inherits as a command-line variable.
release-conformance:
	@test -n "$(ID)" || { echo "Usage: make release-conformance ID=<language id>" >&2; exit 2; }
	$(MAKE) conformance-$(ID)

test-release-manifest:
	$(CARGO) test -p release-manifest

test: test-release-manifest

help-release:
	@echo ""
	@echo "Release:"
	@echo "  make bump-version V=<ver>    - Write the version everywhere, then make codegen"
	@echo "  make release-matrix TAG=<t>  - The release matrix for a tag"
	@echo "  make release-build ID=<id>   - One language's check-codegen + tests"
	@echo "  make release-conformance ID=<id> - One language's conformance suite"
	@echo "  make test-release-manifest   - release-manifest's own tests"

HELP_SECTIONS += release
