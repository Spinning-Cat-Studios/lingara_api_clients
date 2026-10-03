# The PHP library (ADR 29.9.26u; C1 D5's make/<lang>.mk).
#
# php/ is the Composer package spinningcatstudios/lingara, whose `require` is
# PSR interfaces only. Its models come from :codegen's generatePhp
# (openapi-generator's php-nextgen, on the one pin the JVM and Ruby libraries
# share) and its unions, routes and version constants from
# php/codegen/generate.php, a standard-library script. Both halves are
# committed, so building, testing and releasing the package need no JDK: only
# regenerating does.
#
# PHP runs the conformance cases twice, once per built HTTP stack (D7), so
# conformance-php is an explicit rule, which make prefers over the
# conformance-% pattern rule in make/conformance.mk. It has its own recipe,
# so unlike a recipe-less prerequisite rule it may be .PHONY.

LANGS += php

PHP ?= php
COMPOSER ?= composer
GRADLE ?= ./gradlew
GRADLE_FLAGS ?= -q --console=plain
PHP_DIR := php
PHP_SRC := $(PHP_DIR)/src
PHP_VENDOR := $(PHP_DIR)/vendor/autoload.php
# Every generated path under php/src/, relative to it.
PHP_GENERATED := Model ObjectSerializer.php Stream Events/Generated Internal/Operations.php Version.php

PHP_HARNESS := $(PHP) $(PHP_DIR)/conformance/harness.php
CONFORMANCE_CMD_php = $(PHP_HARNESS) --client=symfony
CONFORMANCE_CMD_php_guzzle = $(PHP_HARNESS) --client=guzzle

.PHONY: codegen-php check-codegen-php test-php conformance-php help-php

# The lockfile is a prerequisite only when it exists: the first install
# writes it.
$(PHP_VENDOR): $(PHP_DIR)/composer.json $(wildcard $(PHP_DIR)/composer.lock)
	$(COMPOSER) --working-dir=$(PHP_DIR) install --no-interaction --no-progress
	@touch $@

## The generated models, then the unions, routes and version, from the view.
codegen-php:
	$(GRADLE) $(GRADLE_FLAGS) :codegen:generatePhp
	$(PHP) $(PHP_DIR)/codegen/generate.php

## Fails on any byte of difference from a fresh codegen. Both halves write
## into a temporary directory, never over the working tree, and only the
## generated files are compared: the hand-written core beside them is not.
check-codegen-php:
	@tmp=$$(mktemp -d); status=0; \
	$(GRADLE) $(GRADLE_FLAGS) :codegen:generatePhp -PcodegenOut=$$tmp || status=1; \
	[ $$status -ne 0 ] || $(PHP) $(PHP_DIR)/codegen/generate.php --out $$tmp || status=1; \
	for f in $(PHP_GENERATED); do [ $$status -ne 0 ] || diff -ru $(PHP_SRC)/$$f $$tmp/$$f || status=1; done; \
	rm -rf $$tmp; \
	if [ $$status -ne 0 ]; then echo "✗ php/src's generated files are stale: run make codegen-php"; exit 1; fi; \
	echo "✓ php/src's generated files are current"

## PHPUnit, PHPStan, php-cs-fixer, the PHPCS budgets, and the snippets' syntax.
test-php: $(PHP_VENDOR)
	cd $(PHP_DIR) && vendor/bin/phpunit
	cd $(PHP_DIR) && vendor/bin/phpstan analyse --no-progress --memory-limit=1G
	cd $(PHP_DIR) && vendor/bin/php-cs-fixer check --show-progress=none
	cd $(PHP_DIR) && vendor/bin/phpcs
	@for f in snippets/php/*.php; do $(PHP) -l $$f > /dev/null || exit 1; done; echo "✓ snippets/php parses"

## Both built stacks against every case: Symfony first, then Guzzle. Each run
## starts its own server and reads its own results file.
conformance-php: $(PHP_VENDOR)
	$(CONFORMANCE_SERVER) run --lang php --cases $(CONFORMANCE_CASES) -- $(CONFORMANCE_CMD_php)
	$(CONFORMANCE_SERVER) run --lang php --cases $(CONFORMANCE_CASES) -- $(CONFORMANCE_CMD_php_guzzle)

help-php:
	@echo ""
	@echo "PHP:"
	@echo "  make codegen-php        - Regenerate php/src's models, unions, routes and version"
	@echo "  make check-codegen-php  - Fail when the generated files are stale"
	@echo "  make test-php           - PHPUnit, PHPStan, php-cs-fixer, the budgets, snippet syntax"
	@echo "  make conformance-php    - The harness against every case, on the Symfony and the Guzzle stack"

HELP_SECTIONS += php
