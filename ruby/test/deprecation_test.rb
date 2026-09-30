# frozen_string_literal: true

require "open3"
require "rbconfig"
require "support/fake_server"

class DeprecationTest < Minitest::Test
  OLD = "2026-01-knowing-tenpounder"
  HEADERS = {"Lingara-Version" => OLD, "Deprecation" => "@1790000000", "Sunset" => "Tue, 01 Dec 2026 00:00:00 GMT",
             "Link" => "</v1/versions/#{OLD}>; rel=\"deprecation\"; type=\"application/json\""}.freeze

  class Logger
    attr_reader :warnings, :debugs

    def initialize
      @warnings = []
      @debugs = []
    end

    def warn(message) = @warnings << message

    def debug(message) = @debugs << message
  end

  def serve(headers)
    FakeServer.new { |_, conn| conn.json(200, {current: nil, development: nil, versions: []}, headers) }
  end

  # 29.9.26t AC26: a Deprecation header calls the hook once with parsed times
  # and a resolved link; an unparseable one leaves the field nil; a raising
  # hook does not fail the call; with no hook, one warning per version id
  # reaches Warning.warn at default settings, or a duck-typed logger:.
  def test_deprecation_hook_parsing_and_warn_once
    server = serve(HEADERS)
    notices = []
    server.client(on_deprecation: ->(notice) { notices << notice }, logger: Logger.new).list_api_versions
    assert_equal 1, notices.size
    notice = notices.first
    assert_equal [OLD, 1_790_000_000, Time.utc(2026, 12, 1).to_i], [notice.version, notice.deprecated_at.to_i, notice.sunset_at.to_i]
    assert_equal HEADERS["Link"], notice.link.raw
    assert_equal "#{server.url}/v1/versions/#{OLD}", notice.link.target.to_s

    garbled = serve(HEADERS.merge("Deprecation" => "yesterday", "Sunset" => "soon"))
    notices.clear
    garbled.client(on_deprecation: ->(n) { notices << n }, logger: Logger.new).list_api_versions
    assert_equal ["yesterday", nil, nil], [notices.first.headers["Deprecation"], notices.first.deprecated_at, notices.first.sunset_at]

    logger = Logger.new
    value = server.client(on_deprecation: ->(_) { raise "the hook broke" }, logger: logger).list_api_versions.value
    assert_equal [], value.versions
    assert_equal 1, logger.debugs.size

    logger = Logger.new
    client = server.client(logger: logger)
    3.times { client.list_api_versions }
    assert_equal 1, logger.warnings.count { |w| w.include?("is deprecated") }

    script = "require 'lingara'; c = Lingara::Client.new(base_url: #{server.url.inspect}); 2.times { c.list_api_versions }"
    _, stderr, status = Open3.capture3(RbConfig.ruby, "-I", File.expand_path("../lib", __dir__), "-e", script)
    assert status.success?, stderr
    assert_equal 1, stderr.lines.count { |line| line.include?("Lingara API version #{OLD} is deprecated") }
  ensure
    [server, garbled].each { |s| s&.close }
  end
end
