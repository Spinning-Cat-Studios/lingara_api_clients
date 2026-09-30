# The Rust library (ADR 29.9.26p; C1 D5's make/<lang>.mk).
#
# `rust/` is the `lingara` crate, a member of the root workspace beside its
# codegen (`rust/xtask`), its conformance harness (`rust/conformance`) and its
# snippets (`snippets/rust`). The generated models are committed under
# rust/src/generated/ and never hand-edited: check-codegen-rust is what makes
# a hand edit red.

LANGS += rust

RUST_XTASK := $(CARGO) run -q -p lingara-xtask --
RUST_GENERATED := rust/src/generated

CONFORMANCE_CMD_rust = $(CARGO) run -q -p lingara-conformance-harness

.PHONY: codegen-rust check-codegen-rust test-rust help-rust

## The models and stream routes, from the generator view.
codegen-rust:
	$(RUST_XTASK) codegen

## Fails on any byte of difference from a fresh codegen.
check-codegen-rust:
	@tmp=$$(mktemp -d); \
	$(RUST_XTASK) codegen --out-dir $$tmp >/dev/null && \
	diff -ru $(RUST_GENERATED) $$tmp; \
	status=$$?; rm -rf $$tmp; \
	if [ $$status -ne 0 ]; then echo "✗ $(RUST_GENERATED) is stale: run make codegen-rust"; exit 1; fi; \
	echo "✓ $(RUST_GENERATED) is current"

## Clippy over the four members (the generated module allows itself), the
## unit tests under each TLS feature, the codegen's own test (it reads the
## view, which the packaged crate does not carry), and the snippet build.
test-rust:
	$(CARGO) clippy -p lingara -p lingara-xtask -p lingara-conformance-harness -p lingara-snippets --all-targets -- -D warnings
	$(CARGO) clippy -p lingara --all-targets --no-default-features --features native-tls -- -D warnings
	$(CARGO) test -p lingara
	$(CARGO) test -p lingara --no-default-features --features native-tls
	$(CARGO) test -p lingara-xtask
	$(CARGO) build -p lingara-snippets --examples

# The pattern rule in make/conformance.mk runs the harness (cargo builds it on
# the way). Naming the target here, with no recipe, keeps that recipe while
# defining conformance-rust in a shipped make file, as CI calls it. Not
# .PHONY: a phony target skips the implicit-rule search.
conformance-rust:

help-rust:
	@echo ""
	@echo "Rust:"
	@echo "  make codegen-rust         - Regenerate rust/src/generated from the view"
	@echo "  make check-codegen-rust   - Fail when the generated files are stale"
	@echo "  make test-rust            - Clippy, unit tests under both TLS features, snippets"
	@echo "  make conformance-rust     - The harness against every case"

HELP_SECTIONS += rust
