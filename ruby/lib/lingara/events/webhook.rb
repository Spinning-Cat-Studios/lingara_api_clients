# frozen_string_literal: true

require "json"
require "openssl"

module Lingara
  module Events
    # A webhook that does not verify (CONTRACT.md appendix W; ADR 30.9.26aa
    # D4). It is not a Lingara::Error: no Lingara server answered anything,
    # and a `rescue Lingara::Error` around API calls must not also swallow a
    # forged webhook. #reason is one of REASONS; the message never holds a
    # secret, a signature or the body.
    class VerificationError < StandardError
      REASONS = %i[missing_header malformed_header timestamp_too_old timestamp_too_new no_matching_signature
        malformed_payload].freeze

      attr_reader :reason

      def initialize(reason, detail)
        raise ArgumentError, "unknown verification reason #{reason.inspect}" unless REASONS.include?(reason)
        @reason = reason
        super("webhook verification failed: #{reason}: #{detail}")
      end
    end

    # Verifies a webhook delivery's Standard Webhooks signature, natively:
    # OpenSSL's HMAC-SHA256 and fixed-length compare, and strict base64
    # (ADR 30.9.26aa D4).
    #
    #   webhook = Lingara::Events::Webhook.new(ENV.fetch("LINGARA_WEBHOOK_SECRET"))
    #   event = webhook.verify(request.body.read, request.env)
    #
    # Each secret is `lgr_whsec_` and padded standard base64 of at least 24
    # bytes; during a rotation pass both. +clock+ is the testing seam (C2
    # D9), read for the 300 s tolerance. +headers+ is any Hash, looked up
    # case-insensitively and by Rack's HTTP_WEBHOOK_ID spelling, so a Rack
    # env works as it is. +body+ is the raw body, exactly as received.
    class Webhook
      include Redacted

      PREFIX = "lgr_whsec_"
      MIN_KEY_BYTES = 24
      TOLERANCE = 300
      HEADERS = %w[webhook-id webhook-timestamp webhook-signature].freeze

      def initialize(secret_or_secrets, clock: -> { Time.now })
        secrets = Array(secret_or_secrets)
        raise ArgumentError, "a webhook needs at least one secret" if secrets.empty?
        @keys = secrets.map { |secret| Webhook.key(secret) }.freeze
        @clock = clock
      end

      # The HMAC key a secret names, or ArgumentError. The remainder is
      # matched before it is decoded, because decoders differ in leniency.
      def self.key(secret)
        raise ArgumentError, "a webhook secret is a String starting #{PREFIX}" unless secret.is_a?(String) && secret.start_with?(PREFIX)
        encoded = secret.delete_prefix(PREFIX)
        key = (Webhook.strict_base64(encoded) if encoded.match?(%r{\A[A-Za-z0-9+/]+={0,2}\z}) && (encoded.length % 4).zero?)
        raise ArgumentError, "a webhook secret is #{PREFIX} and padded standard base64" unless key
        raise ArgumentError, "a webhook secret decodes to at least #{MIN_KEY_BYTES} bytes" if key.bytesize < MIN_KEY_BYTES
        key
      end

      # The Event a signed delivery carries, or VerificationError. It stores
      # nothing: deduplicating by #id is the receiver's.
      def verify(body, headers)
        id = signed_id(body, headers)
        event = begin
          Events.parse(body.dup.force_encoding(Encoding::UTF_8))
        rescue Lingara::Error
          raise VerificationError.new(:malformed_payload, "the body is not an event this library can read")
        end
        raise VerificationError.new(:malformed_payload, "the event's id is not webhook-id") unless event.id == id
        event
      end

      # Steps 1–5 alone, for a signed body that is not an event envelope (an
      # app-kit request): nil on success, else VerificationError, never
      # :malformed_payload.
      def verify_signature(body, headers)
        signed_id(body, headers)
        nil
      end

      # Strict (RFC 4648, padded) base64's bytes, or nil: unpack1("m0"),
      # never the base64 gem, which left the default gems.
      def self.strict_base64(text)
        text.unpack1("m0")
      rescue ArgumentError
        nil
      end

      # A header's value, its name compared case-insensitively, or Rack's
      # HTTP_ spelling of it; VerificationError when absent.
      def self.header(headers, name)
        rack = "HTTP_#{name.upcase.tr("-", "_")}"
        headers.each do |key, value|
          key = key.to_s
          next unless key.casecmp?(name) || key == rack
          return value.is_a?(Array) ? value.join(" ") : value.to_s
        end
        raise VerificationError.new(:missing_header, "#{name} is missing")
      end

      def inspect
        "#<Lingara::Events::Webhook secrets=#{REDACTED} x#{@keys.size}>"
      end

      def to_s
        inspect
      end

      private

      # Steps 2–5: the headers, the tolerance and the signatures. Returns
      # webhook-id, which the event's id must equal.
      def signed_id(body, headers)
        raise ArgumentError, "body is the raw request body, a String" unless body.is_a?(String)
        id, timestamp, signatures = HEADERS.map { |name| Webhook.header(headers, name) }
        check_timestamp(timestamp)
        signed = id.b + "." + timestamp.b + "." + body.b
        return id if matches?(signed, signatures)
        raise VerificationError.new(:no_matching_signature, "no v1 signature matches")
      end

      def check_timestamp(timestamp)
        raise VerificationError.new(:malformed_header, "webhook-timestamp is not an integer") unless timestamp.match?(/\A[0-9]+\z/)
        skew = @clock.call.to_i - Integer(timestamp, 10)
        raise VerificationError.new(:timestamp_too_old, "webhook-timestamp is too old") if skew > TOLERANCE
        raise VerificationError.new(:timestamp_too_new, "webhook-timestamp is too new") if skew < -TOLERANCE
      end

      # Every v1 element against every secret, each compare constant-time
      # over the full 32 bytes. Another version, or an element that is not
      # strict base64, is skipped.
      def matches?(signed, signatures)
        expected = @keys.map { |key| OpenSSL::HMAC.digest("SHA256", key, signed) }
        signatures.split(/ /).any? do |element|
          version, encoded = element.split(",", 2)
          next false unless version == "v1" && encoded
          signature = Webhook.strict_base64(encoded)
          next false unless signature&.bytesize == 32
          expected.any? { |mac| OpenSSL.fixed_length_secure_compare(mac, signature) }
        end
      end
    end
  end
end
