# The conformance suite (ADR 29.9.26n D12, D14).
#
# Read after every make/<lang>.mk (see the Makefile), so $(LANGS) is complete
# when `conformance`'s prerequisites expand. A language joins by setting
# CONFORMANCE_CMD_<lang> to its harness command line in its own make file;
# the pattern rule below then runs it. A language that needs more than one
# run (PHP: once per HTTP client) defines conformance-<lang> itself, which
# make prefers over the pattern, and still sets CONFORMANCE_CMD_<lang>.

CONFORMANCE_SERVER := $(CARGO) run -q -p lingara-conformance-server --
CONFORMANCE_CASES := conformance/cases

.PHONY: conformance check-conformance-coverage help-conformance

## Every landed language's harness against every case.
conformance: $(addprefix conformance-,$(LANGS))

conformance-%:
	$(CONFORMANCE_SERVER) run --lang $* --cases $(CONFORMANCE_CASES) -- $(CONFORMANCE_CMD_$*)

# The languages in $(LANGS) that set no harness command. `conformance-%` is a
# pattern rule, so every name "has" the target; only the variable says a
# harness exists.
CONFORMANCE_MISSING = $(strip $(foreach l,$(LANGS),$(if $(strip $(CONFORMANCE_CMD_$(l))),,$(l))))

## Every operation and K1–K6 has a case, every case parses, and every
## landed language has a harness.
check-conformance-coverage:
	$(CONFORMANCE_SERVER) check-coverage --spec $(SPEC_DIR)/openapi.json --view $(SPEC_VIEW_DIR)/openapi.3.1.json --cases $(CONFORMANCE_CASES)
	@if [ -n "$(CONFORMANCE_MISSING)" ]; then \
	  echo "✗ no CONFORMANCE_CMD_<lang> for: $(CONFORMANCE_MISSING)"; exit 1; \
	fi
	@echo "✓ every language in LANGS has a harness: $(or $(LANGS),none yet)"

help-conformance:
	@echo ""
	@echo "Conformance:"
	@echo "  make conformance                 - Every language's harness, every case: $(or $(LANGS),none yet)"
	@echo "  make conformance-<lang>          - One language's harness"
	@echo "  make check-conformance-coverage  - Every operation and K1–K6 has a case; every language has a harness"

HELP_SECTIONS += conformance
