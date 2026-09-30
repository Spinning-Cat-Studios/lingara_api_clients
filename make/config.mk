# Shared variables (ADR 29.9.26m D5).
#
# LANGS starts empty. Each make/<lang>.mk appends `LANGS += <lang>` and
# defines codegen-<lang>, check-codegen-<lang> and test-<lang>; the
# aggregates in make/quality.mk run over whatever has joined.

LANGS :=
HELP_SECTIONS :=

CARGO ?= cargo

SPEC_DIR := spec
SPEC_VIEW_DIR := $(SPEC_DIR)/generator
