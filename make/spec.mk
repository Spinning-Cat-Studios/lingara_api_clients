# The generator view (ADR 29.9.26m D4, D4a).
#
# spec/openapi.json is OpenAPI 3.2, which no mainstream generator reads.
# tools/spec-codegen writes the view every language generates from, in two
# dialects: spec/generator/openapi.3.1.json and spec/generator/openapi.3.0.json.
# The view is committed; `check-spec-view` fails on any byte of difference, so
# a hand-edited view is red. Neither dialect is ever served as a document.
#
# The view is built from the frozen snapshot of the registry's current
# (newest supported/lts) version, never the live spec/openapi.json, which
# describes the unpinnable development version (ADR 30.9.26a §3).

SPEC_CODEGEN := $(CARGO) run -q -p spec-codegen --
SPEC_CODEGEN_ARGS := --registry $(SPEC_DIR)/versions.toml --source $(SPEC_DIR)/SOURCE --out-dir $(SPEC_VIEW_DIR)

.PHONY: spec-view check-spec-view help-spec

spec-view:
	$(SPEC_CODEGEN) $(SPEC_CODEGEN_ARGS)

check-spec-view:
	$(SPEC_CODEGEN) $(SPEC_CODEGEN_ARGS) --check

help-spec:
	@echo ""
	@echo "Spec:"
	@echo "  make spec-view         - Regenerate both dialects of the generator view"
	@echo "  make check-spec-view   - Fail when the committed view differs from the spec"

HELP_SECTIONS += spec
