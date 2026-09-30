# The Go library (ADR 29.9.26q; C1 D5's make/<lang>.mk).
#
# `go/` is the module github.com/Spinning-Cat-Studios/lingara_api_clients/go,
# whose require block is empty. Its generator (`go/codegen`) and conformance
# harness (`go/conformance`) are nested modules, so the module zip carries
# neither, and `snippets/go` is a module of its own. A nested module is
# outside its parent's `./...`, so the targets below name all four.
#
# GOTOOLCHAIN defaults to `local` for every entry point. Under Go's default,
# `auto`, a `go run <tool>@<pin>` may switch to whatever toolchain that tool
# asks for, and CI's Go 1.23 leg would silently run something newer.
#
# Two make rules the conformance lines depend on:
#   - $(GO_HARNESS) is .PHONY, so it is rebuilt on every run. Otherwise an
#     existing binary would never be rebuilt after a library edit, and a local
#     run would test stale code. Go's build cache keeps the rebuild cheap.
#   - conformance-go is never .PHONY. GNU make skips the implicit-rule search
#     for a phony target, which would silently drop the recipe of the
#     conformance-% pattern rule in make/conformance.mk. As an ordinary target
#     with no recipe of its own it takes that recipe, and the harness runs
#     with the repository root as its working directory.

LANGS += go

export GOTOOLCHAIN ?= local

GO ?= go
GOFMT ?= gofmt
GO_DIR := go
GO_VIEW := $(SPEC_VIEW_DIR)/openapi.3.0.json
GO_MODULES := go go/codegen go/conformance snippets/go
GO_GENERATED := models_gen.go streams_gen.go routes_gen.go version_gen.go
GO_HARNESS := target/go-conformance

# Pinned, and run with `go run`, so no go.mod ever names them. oapi-codegen's
# pin builds on the Go 1.23 floor, where C4's build-go runs check-codegen-go;
# the linters run on the stable leg only.
OAPI_CODEGEN := $(GO) run github.com/oapi-codegen/oapi-codegen/v2/cmd/oapi-codegen@v2.6.0
REVIVE := $(GO) run github.com/mgechev/revive@v1.17.0
STATICCHECK := $(GO) run honnef.co/go/tools/cmd/staticcheck@v0.8.1

CONFORMANCE_CMD_go = $(GO_HARNESS)

.PHONY: codegen-go check-codegen-go test-go lint-go help-go $(GO_HARNESS)

# Writes the four *_gen.go files into $(1): oapi-codegen's models (it writes
# to stdout with no `output` key), then go/codegen's three.
define go_codegen
	$(OAPI_CODEGEN) -config $(GO_DIR)/oapi-codegen.yaml $(GO_VIEW) > $(1)/models_gen.go
	$(GO) -C $(GO_DIR)/codegen run . -view $(abspath $(GO_VIEW)) -version $(abspath VERSION) -out $(abspath $(1))
endef

## The generated models, stream unions, routes and version, from the view.
codegen-go:
	$(call go_codegen,$(GO_DIR))

## Fails on any byte of difference from a fresh codegen. It writes into a
## temporary directory, never over the working tree.
check-codegen-go:
	@tmp=$$(mktemp -d); status=0; \
	$(OAPI_CODEGEN) -config $(GO_DIR)/oapi-codegen.yaml $(GO_VIEW) > $$tmp/models_gen.go && \
	$(GO) -C $(GO_DIR)/codegen run . -view $(abspath $(GO_VIEW)) -version $(abspath VERSION) -out $$tmp || status=1; \
	for f in $(GO_GENERATED); do [ $$status -ne 0 ] || diff -u $(GO_DIR)/$$f $$tmp/$$f || status=1; done; \
	rm -rf $$tmp; \
	if [ $$status -ne 0 ]; then echo "✗ go/*_gen.go is stale: run make codegen-go"; exit 1; fi; \
	echo "✓ go/*_gen.go is current"

## gofmt and vet over the four modules; the library's and the generator's
## unit tests under the race detector.
test-go:
	@unformatted=$$($(GOFMT) -l $(GO_MODULES)); \
	if [ -n "$$unformatted" ]; then echo "✗ gofmt -l:"; echo "$$unformatted"; exit 1; fi
	@for m in $(GO_MODULES); do echo "go vet: $$m"; $(GO) -C $$m vet ./... || exit 1; done
	$(GO) -C $(GO_DIR) test -race ./...
	$(GO) -C $(GO_DIR)/codegen test -race ./...

## revive (the budgets in go/revive.toml) and staticcheck over the library.
lint-go:
	cd $(GO_DIR) && $(REVIVE) -config revive.toml -set_exit_status -exclude '*_gen.go' ./...
	cd $(GO_DIR) && $(STATICCHECK) ./...

$(GO_HARNESS):
	$(GO) -C $(GO_DIR)/conformance build -o $(abspath $(GO_HARNESS)) .

# The pattern rule in make/conformance.mk runs the harness. No recipe, and
# not .PHONY (see the header).
conformance-go: $(GO_HARNESS)

help-go:
	@echo ""
	@echo "Go:"
	@echo "  make codegen-go          - Regenerate go/*_gen.go from the view"
	@echo "  make check-codegen-go    - Fail when the generated files are stale"
	@echo "  make test-go             - gofmt, vet over the four modules, unit tests under -race"
	@echo "  make lint-go             - revive (the budgets) and staticcheck"
	@echo "  make conformance-go      - The harness against every case"

HELP_SECTIONS += go
