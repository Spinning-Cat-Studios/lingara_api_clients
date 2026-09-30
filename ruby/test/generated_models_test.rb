# frozen_string_literal: true

require "open3"
require "rbconfig"
require "support/fake_server"

class GeneratedModelsTest < Minitest::Test
  LIB = File.expand_path("../lib", __dir__)
  GENERATED = Dir[File.join(LIB, "lingara/models/*.rb")].sort + %w[operations streams version].map { |f| File.join(LIB, "lingara/#{f}.rb") }
  STANDARD = %w[date time json].freeze

  # 29.9.26t AC2: every generated file loads with only lib/ on the load path
  # and with no gem activated, and none references the generator's client
  # support (ApiClient, Configuration) or requires anything outside the
  # standard library.
  def test_generated_models_load_without_client_support
    refute_empty GENERATED
    script = "require 'lingara/errors'; require 'lingara/response'; " \
      "#{GENERATED.map { |f| "require #{f.inspect}" }.join("; ")}; puts :ok"
    out, err, status = Open3.capture3(RbConfig.ruby, "--disable-gems", "-I", LIB, "-e", script)
    assert status.success?, err
    assert_equal "ok\n", out
    GENERATED.each do |file|
      text = File.read(file)
      refute_match(/\b(ApiClient|Configuration)\b/, text, file)
      requires = text.scan(/^\s*require\s+['"]([^'"]+)['"]/).flatten
      assert_empty requires - STANDARD, file
    end
  end
end
