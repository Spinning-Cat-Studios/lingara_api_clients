# frozen_string_literal: true

require "json"

module Lingara
  # K3: one error family (CONTRACT.md K3). Every failure a call raises, a
  # caller's own interrupt aside, is one of the four subclasses below, so one
  # `rescue Lingara::Error` handles them all.
  class Error < StandardError
    # The fields a subclass renders in #inspect, never a secret.
    def inspect
      fields = self.class::FIELDS.map { |name| "#{name}=#{public_send(name).inspect}" }
      "#<#{self.class.name} #{fields.join(" ")}>"
    end

    alias_method :pretty_print_inspect, :inspect

    def pretty_print(q)
      q.text(inspect)
    end
  end

  # A /v1 refusal, or a stream's error event (then +status+ is 200).
  class ApiError < Error
    FIELDS = %i[status code message retry_after plan_id served_version].freeze

    attr_reader :status, :code, :retry_after, :plan_id, :served_version

    def initialize(status:, code:, message:, retry_after: nil, plan_id: nil, served_version: nil)
      @status = status
      @code = code
      @retry_after = retry_after
      @plan_id = plan_id
      @served_version = served_version
      super("#{code}: #{message} (HTTP #{status})")
      @detail = message
    end

    # The envelope's `error`, or the event's `message`. #to_s keeps the code
    # and the status beside it.
    def message
      @detail || super
    end
  end

  # A token-endpoint refusal: RFC 6749 §5.2, or `http_<status>`.
  class OAuthError < Error
    FIELDS = %i[status error description retry_after].freeze

    attr_reader :status, :error, :description, :retry_after

    def initialize(status:, error:, description: nil, retry_after: nil)
      @status = status
      @error = error
      @description = description
      @retry_after = retry_after
      super(description ? "token endpoint: #{error}: #{description} (HTTP #{status})" : "token endpoint: #{error} (HTTP #{status})")
    end
  end

  # A 503 whose body is not JSON: the service is in maintenance.
  class MaintenanceError < Error
    FIELDS = %i[body retry_after].freeze

    attr_reader :body, :retry_after

    def initialize(body:, retry_after: nil)
      @body = body
      @retry_after = retry_after
      super("the API is under maintenance")
    end
  end

  # A call with no usable HTTP answer. +kind+ is one of KINDS; +cause+ is the
  # underlying failure, scrubbed when its text named a credential.
  class TransportError < Error
    FIELDS = %i[kind].freeze
    KINDS = %i[connect tls reset timeout stream_ended_early malformed_response malformed_event].freeze

    attr_reader :kind

    def initialize(kind, detail = nil)
      raise ArgumentError, "unknown transport kind #{kind.inspect}" unless KINDS.include?(kind)
      @kind = kind
      super(detail ? "transport failure: #{kind}: #{detail}" : "transport failure: #{kind}")
    end
  end

  # Stands in for an underlying error whose text named a credential, as the
  # +cause+ of the TransportError that replaces it.
  class ScrubbedCause < StandardError
    def initialize
      super("the underlying error was withheld: it contained a credential")
    end
  end

  # Response → error, in C2 D4's precedence.
  module Refusal
    MAINTENANCE_BODY_BYTES = 1024

    module_function

    # A non-2xx response to its K3 class. +endpoint+ is :v1 or :token; +now+
    # reads an HTTP-date Retry-After.
    def error(endpoint, response, body, now)
      status = response.code.to_i
      retry_after = RetryPolicy.parse_retry_after(response["Retry-After"], now)
      if status == 503 && !json?(response)
        return MaintenanceError.new(body: truncate(body.to_s, MAINTENANCE_BODY_BYTES), retry_after: retry_after)
      end
      fields = parse_object(body)
      if endpoint == :token
        return oauth_error(status, fields, retry_after)
      end
      api_error(status, fields, retry_after, response["Lingara-Version"])
    end

    def oauth_error(status, fields, retry_after)
      code = fields["error"]
      if code.is_a?(String)
        description = fields["error_description"]
        OAuthError.new(status: status, error: code, description: description.is_a?(String) ? description : nil, retry_after: retry_after)
      else
        OAuthError.new(status: status, error: "http_#{status}", retry_after: retry_after)
      end
    end

    def api_error(status, fields, retry_after, served_version)
      code = fields["code"]
      message = fields["error"]
      unless code.is_a?(String) && message.is_a?(String)
        code = "http_#{status}"
        message = "HTTP #{status}"
      end
      ApiError.new(status: status, code: code, message: message, retry_after: retry_after, served_version: served_version)
    end

    # A stream's error event: ApiError with status 200. K5 raises it at
    # once; the tail (K5a) only once its failures are spent.
    def stream_error(data, served_version)
      fields = parse_object(data)
      text = ->(key, fallback) { fields[key].is_a?(String) ? fields[key] : fallback }
      ApiError.new(status: 200, code: text.call("code", "stream_error"), message: text.call("message", "the stream reported an error"),
        plan_id: text.call("plan_id", nil), served_version: served_version)
    end

    def parse_object(body)
      value = JSON.parse(body.to_s)
      value.is_a?(Hash) ? value : {}
    rescue JSON::ParserError
      {}
    end

    # The Content-Type's media type, parameters dropped and lower-cased.
    def media_type(response)
      response["Content-Type"].to_s.split(";").first.to_s.strip.downcase
    end

    def json?(response)
      media = media_type(response)
      media == "application/json" || media.end_with?("+json")
    end

    # At most +max+ bytes, cut on a character boundary.
    def truncate(text, max)
      text = text.dup.force_encoding(Encoding::UTF_8)
      return text if text.bytesize <= max
      text.byteslice(0, max).scrub("")
    end
  end
end
