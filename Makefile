# Lingara API client libraries
# ============================
#
# The files included below are the public build: they ship. Tooling that
# names private repositories or checkouts is pulled in by ONE optional include
# near the end, so a checkout without it still builds, tests and helps.
#
# The include order is load-bearing (ADR 29.9.26m D5). A prerequisite list is
# expanded when make reads the rule, so the aggregates in make/quality.mk (and
# the conformance aggregate after it) must be read after every language file
# has appended itself to LANGS; read earlier, `check-codegen` would expand
# over an empty list and pass while checking nothing. A language joins by
# adding its make/<lang>.mk and nothing else: this file never changes for it.

include make/config.mk
-include make/typescript.mk make/rust.mk make/go.mk make/java.mk make/kotlin.mk make/ruby.mk make/php.mk
include make/spec.mk make/quality.mk
include make/release.mk
-include make/conformance.mk
-include make/private.mk

# Staging carries its own release targets in make/staging.mk, which is copied
# to staging and never promoted. The private checkout sets PRIVATE_MAKE_LOADED
# instead, so there this file is never read.
ifndef PRIVATE_MAKE_LOADED
-include make/staging.mk
endif

.DEFAULT_GOAL := help

# Each included file appends its section to HELP_SECTIONS, so an absent
# optional file drops its section instead of failing `help`.
.PHONY: help
help:
	@echo "Lingara API client libraries"
	@echo "============================"
	@for section in $(HELP_SECTIONS); do $(MAKE) -s help-$$section || exit 1; done
