# frozen_string_literal: true

module Lingara
  # K6: `lingara-ruby/<VERSION> (ruby/<RUBY_VERSION>; <RUBY_PLATFORM>)`, the
  # library's token first and a caller's suffix after one space. VERSION is
  # the SemVer spelling, never GEM_VERSION, whose `.pre.` the contract's
  # pattern refuses.
  module UserAgent
    # Visible ASCII with no `)`: CONTRACT.md K6's <runtime>.
    RUNTIME = /\A[\x20-\x28\x2A-\x7E]+\z/

    module_function

    def build(suffix = nil, version: VERSION, runtime: default_runtime)
      runtime = "ruby/unknown" unless runtime.match?(RUNTIME)
      agent = "lingara-ruby/#{version} (#{runtime})"
      (suffix.nil? || suffix.empty?) ? agent : "#{agent} #{suffix}"
    end

    def default_runtime
      "ruby/#{RUBY_VERSION}; #{RUBY_PLATFORM}"
    end
  end
end
