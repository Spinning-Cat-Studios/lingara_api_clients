# The Ruby library (ADR 29.9.26t; C1 D5's make/<lang>.mk).
#
# ruby/ is the gem `lingara`, with no runtime dependency. Its models come
# from :codegen's generateRuby (openapi-generator, the one pin the JVM
# libraries share) and its unions, routes and version constants from
# ruby/codegen/generate.rb, a standard-library script. Both halves are
# committed, so building, testing and releasing the gem need no JDK: only
# regenerating does.
#
# The harness is a script with nothing to build, so conformance-ruby is the
# conformance-% pattern rule in make/conformance.mk unchanged. It is never
# .PHONY: GNU make skips the implicit-rule search for a phony target, which
# would silently drop that rule's recipe.

LANGS += ruby

RUBY ?= ruby
BUNDLE ?= bundle
GRADLE ?= ./gradlew
GRADLE_FLAGS ?= -q --console=plain
RUBY_DIR := ruby
RUBY_LIB := $(RUBY_DIR)/lib/lingara
RUBY_SCRIPT_OUTPUTS := operations.rb streams.rb version.rb
RUBY_LOCK := $(RUBY_DIR)/Gemfile.lock
# Gemfile.lock records the gem's own version, and a frozen bundle (CI's)
# refuses a lockfile that disagrees with version.rb. So the lockfile's version
# line is generated with version.rb, and checked with it.
RUBY_GEM_VERSION = $(RUBY) -I$(RUBY_DIR)/lib -rlingara/version -e 'print Lingara::GEM_VERSION'

CONFORMANCE_CMD_ruby = $(RUBY) -I$(RUBY_DIR)/lib $(RUBY_DIR)/conformance/harness.rb

.PHONY: codegen-ruby check-codegen-ruby test-ruby help-ruby

## The generated models, then the unions, routes and version, from the view.
codegen-ruby:
	$(GRADLE) $(GRADLE_FLAGS) :codegen:generateRuby
	$(RUBY) $(RUBY_DIR)/codegen/generate.rb
	@v=$$($(RUBY_GEM_VERSION)) && $(RUBY) -e 'f = ARGV[0]; File.write(f, File.read(f).sub(/^    lingara \(.+\)$$/, "    lingara (#{ARGV[1]})"))' $(RUBY_LOCK) "$$v"

## Fails on any byte of difference from a fresh codegen. Both halves write
## into a temporary directory, never over the working tree, and only the
## generated files are compared: the hand-written core beside them is not.
check-codegen-ruby:
	@tmp=$$(mktemp -d); status=0; \
	$(GRADLE) $(GRADLE_FLAGS) :codegen:generateRuby -PcodegenOut=$$tmp/models || status=1; \
	[ $$status -ne 0 ] || $(RUBY) $(RUBY_DIR)/codegen/generate.rb --out $$tmp || status=1; \
	[ $$status -ne 0 ] || diff -ru $(RUBY_LIB)/models $$tmp/models || status=1; \
	for f in $(RUBY_SCRIPT_OUTPUTS); do [ $$status -ne 0 ] || diff -u $(RUBY_LIB)/$$f $$tmp/$$f || status=1; done; \
	[ $$status -ne 0 ] || grep -qxF "    lingara ($$($(RUBY_GEM_VERSION)))" $(RUBY_LOCK) || { echo "✗ $(RUBY_LOCK) does not lock lingara at version.rb's GEM_VERSION"; status=1; }; \
	rm -rf $$tmp; \
	if [ $$status -ne 0 ]; then echo "✗ ruby/lib/lingara's generated files are stale: run make codegen-ruby"; exit 1; fi; \
	echo "✓ ruby/lib/lingara's generated files are current"

## The unit, codegen and snippet tests; standardrb; the budgets and the file
## length; rbs validate; the snippets' syntax.
test-ruby:
	cd $(RUBY_DIR) && $(BUNDLE) exec rake test
	cd $(RUBY_DIR) && $(BUNDLE) exec standardrb
	cd $(RUBY_DIR) && $(BUNDLE) exec rubocop --config .rubocop-budgets.yml
	cd $(RUBY_DIR) && $(BUNDLE) exec rake budget:file_length
	cd $(RUBY_DIR) && $(BUNDLE) exec rbs -I sig validate
	@for f in snippets/ruby/*.rb; do $(RUBY) -wc $$f > /dev/null || exit 1; done; echo "✓ snippets/ruby parses under -w"

# The pattern rule in make/conformance.mk runs the harness. No recipe, and
# not .PHONY (see the header).
conformance-ruby:

help-ruby:
	@echo ""
	@echo "Ruby:"
	@echo "  make codegen-ruby        - Regenerate ruby/lib/lingara's models, unions, routes and version, and Gemfile.lock's version line"
	@echo "  make check-codegen-ruby  - Fail when the generated files are stale"
	@echo "  make test-ruby           - Unit tests, standardrb, the budgets, rbs validate, snippet syntax"
	@echo "  make conformance-ruby    - The harness against every case"

HELP_SECTIONS += ruby
