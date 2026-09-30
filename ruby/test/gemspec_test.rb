# frozen_string_literal: true

require "open3"
require "rbconfig"
require "support/fake_server"

class GemspecTest < Minitest::Test
  RUBY_DIR = File.expand_path("..", __dir__)
  GEMSPEC = File.join(RUBY_DIR, "lingara.gemspec")

  # 29.9.26t AC1: no runtime dependency, a 3.3 floor, neither require_paths
  # nor bindir set (so the publisher reads it without running it), and the
  # licence a byte-equal copy of the root file.
  def test_gemspec_has_no_runtime_dependency_and_a_static_shape
    spec = Gem::Specification.load(GEMSPEC)
    assert_equal "lingara", spec.name
    assert_equal Lingara::GEM_VERSION, spec.version.to_s
    assert_empty spec.runtime_dependencies
    assert_equal Gem::Requirement.new(">= 3.3"), spec.required_ruby_version
    assert_equal ["lib"], spec.require_paths
    text = File.read(GEMSPEC)
    refute_match(/^\s*[^#\n]*\brequire_paths\s*=/, text)
    refute_match(/^\s*[^#\n]*\bbindir\s*=/, text)
    assert_includes spec.files, "LICENSE"
    assert_includes spec.files, "lib/lingara.rb"
    assert_equal File.binread(File.join(RUBY_DIR, "..", "LICENSE")), File.binread(File.join(RUBY_DIR, "LICENSE"))
  end

  # 29.9.26t AC30: requiring the gem prints nothing at default verbosity, and
  # under -w no warning names a file outside lib/lingara/models/.
  def test_require_is_silent_outside_generated_models
    lib = File.join(RUBY_DIR, "lib")
    _, quiet, = Open3.capture3(RbConfig.ruby, "-I", lib, "-e", "require 'lingara'")
    assert_equal "", quiet
    _, verbose, = Open3.capture3(RbConfig.ruby, "-w", "-I", lib, "-e", "require 'lingara'")
    outside = verbose.lines.grep(/warning/).reject { |line| line.include?("lib/lingara/models/") }
    assert_empty outside
  end
end
