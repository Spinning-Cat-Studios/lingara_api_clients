# frozen_string_literal: true

module Lingara
  # Renders a secret-holding object as [REDACTED] everywhere Ruby renders
  # one: #inspect, #to_s and pp (K1, redaction).
  module Redacted
    REDACTED = "[REDACTED]"

    def pretty_print(q)
      q.text(inspect)
    end
  end

  # An opaque access token, as a token source returns it. It renders as
  # [REDACTED]; #expose_secret is the one way to read it. A caller's own
  # token source builds one with AccessToken.new(raw).
  class AccessToken
    include Redacted

    def initialize(raw)
      raise ArgumentError, "an access token is a non-empty String" unless raw.is_a?(String) && !raw.empty?
      @raw = raw.dup.freeze
    end

    # The raw token: the one accessor that does not redact.
    def expose_secret
      @raw
    end

    def ==(other)
      other.is_a?(AccessToken) && other.expose_secret == @raw
    end
    alias_method :eql?, :==

    def hash
      @raw.hash
    end

    def to_s
      REDACTED
    end

    def inspect
      "#<Lingara::AccessToken #{REDACTED}>"
    end
  end
end
