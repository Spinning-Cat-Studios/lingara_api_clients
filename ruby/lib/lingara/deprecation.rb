# frozen_string_literal: true

require "time"
require "uri"

module Lingara
  # A Link header: its raw value, and its target resolved against the
  # request URL (RFC 8288 §3.2), or nil when it has none.
  DeprecationLink = Struct.new(:raw, :target)

  # What a response under a deprecated version says about it (K2). An
  # unparseable header leaves its parsed field nil, never an error.
  # +deprecated_at+ and +sunset_at+ are Times; +headers+ are the raw
  # Deprecation, Sunset and Link values.
  DeprecationNotice = Struct.new(:version, :deprecated_at, :sunset_at, :link, :headers) do
    # The notice for a response, or nil when it carries no Deprecation.
    def self.from(response, request_url)
      raw = response["Deprecation"]
      return nil if raw.nil? || raw.strip.empty?
      sunset = response["Sunset"]
      link = response["Link"]
      new(
        version: response["Lingara-Version"],
        deprecated_at: parse_deprecation(raw),
        sunset_at: parse_sunset(sunset),
        link: link && parse_link(link, request_url),
        headers: {"Deprecation" => raw, "Sunset" => sunset, "Link" => link}.compact
      )
    end

    def self.parse_deprecation(value)
      digits = value.strip.delete_prefix("@")
      return nil unless value.strip.start_with?("@") && digits.match?(/\A-?\d+\z/)
      Time.at(digits.to_i).utc
    end

    # An IMF-fixdate only: Time.httpdate also reads the two obsolete formats,
    # which Sunset does not allow.
    def self.parse_sunset(value)
      return nil if value.nil?
      text = value.strip
      return nil unless text.match?(/\A[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT\z/)
      Time.httpdate(text)
    rescue ArgumentError
      nil
    end

    def self.parse_link(raw, request_url)
      target = raw.strip[/\A<([^>]*)>/, 1]
      resolved = target && URI.join(request_url, target)
      DeprecationLink.new(raw: raw, target: resolved)
    rescue URI::Error
      DeprecationLink.new(raw: raw, target: nil)
    end
  end

  # The default logger: #warn goes through Kernel#warn with no category, so
  # it reaches Warning.warn and $stderr by default and `ruby -W0` silences
  # it; #debug writes only under $DEBUG. Any object with #warn and #debug,
  # such as Rails.logger, replaces it.
  class DefaultLogger
    def warn(message)
      Kernel.warn(message)
    end

    def debug(message)
      Kernel.warn(message) if $DEBUG
    end
  end

  # Per client: reads the served version off each response and reports a
  # deprecation once per response, to the hook or, with no hook, as one
  # warning per version id. Separately, it warns once per served id that is
  # not the version the models were generated from (ADR 30.9.26a §4).
  class VersionObserver
    def initialize(hook:, logger:)
      @hook = hook
      @logger = logger
      @mutex = Mutex.new
      @warned = {}
      # Its own set: sharing @warned would let a version that is both
      # deprecated and mismatched warn only once in total.
      @mismatched = {}
    end

    # The Lingara-Version echo, or nil, after reporting any deprecation or
    # mismatch.
    def observe(response, request_url)
      notice = DeprecationNotice.from(response, request_url)
      report(notice) if notice
      served = response["Lingara-Version"]
      served = nil if served && served.empty?
      check_generated(served) if served
      served
    end

    private

    def report(notice)
      return warn_deprecated(notice) unless @hook
      begin
        @hook.call(notice)
      rescue => e
        @logger.debug("the Lingara deprecation hook raised #{e.class}; the call continues")
      end
    end

    def warn_deprecated(notice)
      # An absent echo counts as one id.
      return unless first_time?(@warned, notice.version.to_s)
      sunset = notice.headers["Sunset"] ? "; sunset #{notice.headers["Sunset"]}" : ""
      @logger.warn("Lingara API version #{notice.version || "(unnamed)"} is deprecated#{sunset}. See GET /v1/versions.")
    end

    def check_generated(served)
      return if served == GENERATED_FOR_VERSION || !first_time?(@mismatched, served)
      @logger.warn("Lingara API version #{served} served this response, but this library's models were generated for " \
        "#{GENERATED_FOR_VERSION}; response shapes may differ. Pin the OAuth client to #{GENERATED_FOR_VERSION} or upgrade the library.")
    end

    def first_time?(seen, id)
      @mutex.synchronize do
        next false if seen[id]
        seen[id] = true
      end
    end
  end
end
