# The aggregates over $(LANGS) (ADR 29.9.26m D5).
#
# Read after every make/<lang>.mk (see the Makefile): these prerequisite
# lists are expanded when this file is read. A language never edits this
# file; appending itself to LANGS is the whole of joining.

.PHONY: codegen check-codegen test test-spec-codegen check-publishable help-quality

## Regenerate every language's generated code.
codegen: $(addprefix codegen-,$(LANGS))

## The view is current, and so is every language's generated code.
check-codegen: check-spec-view $(addprefix check-codegen-,$(LANGS))

test: test-spec-codegen $(addprefix test-,$(LANGS))

test-spec-codegen:
	$(CARGO) test -p spec-codegen

## What a published snapshot must pass: run before every publish.
## `languages.toml` agrees with the tree (make/release.mk, ADR 29.9.26v D2).
check-publishable: check-spec-view check-codegen
	$(RELEASE_MANIFEST) check

help-quality:
	@echo ""
	@echo "Quality:"
	@echo "  make codegen             - Regenerate every language: $(or $(LANGS),none yet)"
	@echo "  make check-codegen       - The view and every language's generated code are current"
	@echo "  make test                - spec-codegen's tests and every language's"
	@echo "  make check-publishable   - What a publish must pass"

HELP_SECTIONS += quality
