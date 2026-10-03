# frozen_string_literal: true

require "json"

module Lingara
  # A JSON operation's result: #value is the generated response model (a
  # Hash for getOpenApiDocument) and #served_version the Lingara-Version
  # echo, or nil when the server sent none.
  Response = Struct.new(:value, :served_version)

  # What a stream's block form returns once its terminal event is handled.
  StreamResult = Struct.new(:served_version)

  # Every call into generated decoding runs here, under one rescue: the
  # generated models' writers raise on a missing required field, an
  # out-of-range value or an unknown enum value, and that must stay inside
  # K3 (D2, the decode rule). Only a pure, non-blocking step is wrapped.
  module Decoding
    module_function

    # A JSON response body as +model+ (a generated class name, or nil for a
    # plain Hash).
    def response(model, body)
      value = JSON.parse(body)
      return value if model.nil? && value.is_a?(Hash)
      raise TransportError.new(:malformed_response, "the response body is not a JSON object") unless value.is_a?(Hash)
      Lingara.const_get(model).build_from_hash(value)
    rescue Lingara::Error
      raise
    rescue
      raise TransportError.new(:malformed_response, "the response body does not decode")
    end

    # One page of the feed (ADR 30.9.26aa D6) as its parsed Hash: the items
    # stay raw for Events.decode, so only the page's own shape is checked.
    def page(body)
      value = JSON.parse(body)
      shaped = value.is_a?(Hash) && value["items"].is_a?(Array) && value["next_cursor"].is_a?(String) &&
        [true, false].include?(value["has_more"])
      raise TransportError.new(:malformed_response, "the response body is not an event page") unless shaped
      value
    rescue JSON::ParserError
      raise TransportError.new(:malformed_response, "the response body is not JSON")
    end

    # One known event as its generated branch. A +data+ that is not a JSON
    # object, or that the branch refuses, is :malformed_event.
    def event(branch, name, data)
      parsed = JSON.parse(data)
      raise TransportError.new(:malformed_event, "#{name}: data is not a JSON object") unless parsed.is_a?(Hash)
      branch.build_from_hash({"event" => name, "data" => parsed})
    rescue Lingara::Error
      raise
    rescue
      raise TransportError.new(:malformed_event, "#{name}: data does not decode")
    end
  end
end
