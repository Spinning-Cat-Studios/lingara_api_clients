# The Kotlin library (ADR 29.9.26s; C1 D5's make/<lang>.mk).
#
# kotlin/ is the Gradle project :kotlin in the repo-root build Java and Kotlin
# share. Its generated half comes from :codegen's generateKotlin, its harness
# is :kotlin:conformance and its snippets :snippets:kotlin; every target below
# goes through the wrapper, so a JDK 17 is the only thing a machine needs.
#
# KOTLIN_TEST_JDK is empty by default. Set, it runs the unit tests on that
# toolchain (-PtestJdk) while compilation stays on 17: CI's newest-LTS leg is
# `make test-kotlin KOTLIN_TEST_JDK=<n>`, never a bare ./gradlew.
#
# Two make rules the conformance lines depend on, as in make/java.mk:
#   - kotlin-harness is .PHONY, so installDist runs every time and a local run
#     never tests a stale harness; Gradle's up-to-date checks keep it cheap.
#   - conformance-kotlin is never .PHONY. GNU make skips the implicit-rule
#     search for a phony target, which would silently drop the recipe of the
#     conformance-% pattern rule in make/conformance.mk.

LANGS += kotlin

GRADLE ?= ./gradlew
GRADLE_FLAGS ?= -q --console=plain
KOTLIN_TEST_JDK ?=
KOTLIN_GENERATED := kotlin/src/generated/kotlin
KOTLIN_HARNESS := kotlin/conformance/build/install/conformance/bin/conformance

CONFORMANCE_CMD_kotlin = $(KOTLIN_HARNESS)

.PHONY: codegen-kotlin check-codegen-kotlin test-kotlin kotlin-harness help-kotlin

## The generated models, stream unions, Streams and BuildInfo, from the view.
codegen-kotlin:
	$(GRADLE) $(GRADLE_FLAGS) :codegen:generateKotlin

## Fails on any byte of difference from a fresh codegen. It syncs into a
## temporary directory, never over the working tree, so an unrelated
## uncommitted edit cannot fail it.
check-codegen-kotlin:
	@tmp=$$(mktemp -d); status=0; \
	$(GRADLE) $(GRADLE_FLAGS) :codegen:generateKotlin -PcodegenOut=$$tmp || status=1; \
	[ $$status -ne 0 ] || diff -ru $(KOTLIN_GENERATED) $$tmp || status=1; \
	rm -rf $$tmp; \
	if [ $$status -ne 0 ]; then echo "✗ kotlin/src/generated/kotlin is stale: run make codegen-kotlin"; exit 1; fi; \
	echo "✓ kotlin/src/generated/kotlin is current"

## StreamsCodegen's tests; Spotless and the JUnit suite; the snippets compile.
test-kotlin:
	$(GRADLE) $(GRADLE_FLAGS) $(if $(KOTLIN_TEST_JDK),-PtestJdk=$(KOTLIN_TEST_JDK)) :codegen:test :kotlin:check :snippets:kotlin:compileKotlin

kotlin-harness:
	$(GRADLE) $(GRADLE_FLAGS) :kotlin:conformance:installDist

# The pattern rule in make/conformance.mk runs the harness. No recipe, and
# not .PHONY (see the header).
conformance-kotlin: kotlin-harness

help-kotlin:
	@echo ""
	@echo "Kotlin:"
	@echo "  make codegen-kotlin        - Regenerate kotlin/src/generated/kotlin from the view"
	@echo "  make check-codegen-kotlin  - Fail when the generated sources are stale"
	@echo "  make test-kotlin           - Spotless, JUnit, StreamsCodegen, snippets (KOTLIN_TEST_JDK=<n>)"
	@echo "  make conformance-kotlin    - The harness against every case"

HELP_SECTIONS += kotlin
