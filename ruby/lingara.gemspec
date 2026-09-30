# frozen_string_literal: true

# The gem `lingara` (ADR 29.9.26t D1). No runtime dependency: the core is
# the standard library only. require_paths stays at its default and no
# bindir is set, so the publisher reads this file without running it.

require_relative "lib/lingara/version"

Gem::Specification.new do |spec|
  spec.name = "lingara"
  spec.version = Lingara::GEM_VERSION
  spec.authors = ["Spinning Cat Studios"]
  spec.summary = "The official Ruby library for the Lingara API"
  spec.description = "Generate vocabulary lists and lesson plans, and hold tutor conversations, " \
    "through the Lingara API. Streams are blocks or Enumerables; the standard library is the only dependency."
  spec.homepage = "https://github.com/Spinning-Cat-Studios/lingara_api_clients"
  spec.license = "MIT"
  spec.required_ruby_version = ">= 3.3"
  spec.metadata = {
    "source_code_uri" => "https://github.com/Spinning-Cat-Studios/lingara_api_clients/tree/main/ruby",
    "changelog_uri" => "https://github.com/Spinning-Cat-Studios/lingara_api_clients/blob/main/CHANGELOG.md",
    "rubygems_mfa_required" => "true"
  }
  spec.files = Dir[File.join(__dir__, "{lib/**/*.rb,sig/**/*.rbs}")].map { |path| path.delete_prefix("#{__dir__}/") } +
    %w[README.md LICENSE]
end
