# The TypeScript library (ADR 29.9.26o; C1 D5's make/<lang>.mk).
#
# `typescript/` is an npm package with no runtime dependencies. Every target
# that needs the pinned dev tools depends on $(TS_STAMP), a stamped
# `npm ci`, so a fresh checkout installs once and a changed lockfile
# reinstalls.
#
# The conformance harness is built from the package's own `dist/` and run on
# $(TS_RUNTIME): `node` (the default), `deno` or `bun`. CI's matrix sets it,
# so the one pattern rule in make/conformance.mk serves every runtime.

LANGS += typescript

TS_DIR := typescript
TS_NPM := npm --prefix $(TS_DIR)
TS_BIN := $(TS_DIR)/node_modules/.bin
TS_STAMP := $(TS_DIR)/node_modules/.lingara-ci-stamp
TS_VIEW := $(SPEC_VIEW_DIR)/openapi.3.1.json

TS_RUNTIME ?= node
ifeq ($(TS_RUNTIME),deno)
TS_RUNTIME_CMD := deno run --allow-net --allow-env --allow-read --allow-write
else
TS_RUNTIME_CMD := $(TS_RUNTIME)
endif

CONFORMANCE_CMD_typescript = $(TS_RUNTIME_CMD) $(TS_DIR)/conformance/dist/harness.mjs

.PHONY: codegen-typescript check-codegen-typescript test-typescript build-typescript help-typescript

$(TS_STAMP): $(TS_DIR)/package.json $(TS_DIR)/package-lock.json
	$(TS_NPM) ci --no-audit --no-fund
	@touch $@

# Writes $(1)/schema.ts, $(1)/streams.ts, $(1)/events.ts (ADR 30.9.26aa D3)
# and $(1)/specVersion.ts (ADR 30.9.26a §4) from the 3.1 view.
define ts_codegen
	$(TS_BIN)/openapi-typescript $(TS_VIEW) -o $(1)/schema.ts --silent
	node $(TS_DIR)/scripts/codegenStreams.mjs $(TS_VIEW) $(1)/streams.ts
	node $(TS_DIR)/scripts/codegenEvents.mjs $(TS_VIEW) $(1)/events.ts
	node $(TS_DIR)/scripts/codegenSpecVersion.mjs $(TS_VIEW) $(1)/specVersion.ts
endef

## The generated types and stream routes, from the generator view.
codegen-typescript: $(TS_STAMP)
	$(call ts_codegen,$(TS_DIR)/src/generated)

## Fails on any byte of difference from a fresh codegen.
check-codegen-typescript: $(TS_STAMP)
	@tmp=$$(mktemp -d); \
	$(TS_BIN)/openapi-typescript $(TS_VIEW) -o $$tmp/schema.ts --silent && \
	node $(TS_DIR)/scripts/codegenStreams.mjs $(TS_VIEW) $$tmp/streams.ts && \
	node $(TS_DIR)/scripts/codegenEvents.mjs $(TS_VIEW) $$tmp/events.ts && \
	node $(TS_DIR)/scripts/codegenSpecVersion.mjs $(TS_VIEW) $$tmp/specVersion.ts && \
	diff -u $(TS_DIR)/src/generated/schema.ts $$tmp/schema.ts && \
	diff -u $(TS_DIR)/src/generated/streams.ts $$tmp/streams.ts && \
	diff -u $(TS_DIR)/src/generated/events.ts $$tmp/events.ts && \
	diff -u $(TS_DIR)/src/generated/specVersion.ts $$tmp/specVersion.ts; \
	status=$$?; rm -rf $$tmp; \
	if [ $$status -ne 0 ]; then echo "✗ typescript/src/generated is stale: run make codegen-typescript"; exit 1; fi; \
	echo "✓ typescript/src/generated is current"

## The two bundles, their declarations and the conformance harness.
build-typescript: $(TS_STAMP)
	$(TS_NPM) run build

## Lint budgets, strict types, unit tests, the build, the package checks and
## the snippets' type-check.
test-typescript: $(TS_STAMP)
	cd $(TS_DIR) && ./node_modules/.bin/eslint .
	cd $(TS_DIR) && ./node_modules/.bin/tsc --noEmit
	cd $(TS_DIR) && ./node_modules/.bin/vitest run
	$(TS_NPM) run build
	cd $(TS_DIR) && ./node_modules/.bin/publint --strict
	cd $(TS_DIR) && ./node_modules/.bin/attw --pack .
	$(TS_BIN)/tsc --noEmit -p snippets/typescript

# conformance-typescript needs the built harness first; the pattern rule in
# make/conformance.mk does the rest.
conformance-typescript: build-typescript

help-typescript:
	@echo ""
	@echo "TypeScript:"
	@echo "  make codegen-typescript        - Regenerate typescript/src/generated from the view"
	@echo "  make check-codegen-typescript  - Fail when the generated files are stale"
	@echo "  make test-typescript           - ESLint, tsc, vitest, build, publint, attw, snippets"
	@echo "  make conformance-typescript    - The harness on TS_RUNTIME=$(TS_RUNTIME) (node, deno or bun)"

HELP_SECTIONS += typescript
