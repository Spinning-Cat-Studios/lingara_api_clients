# frozen_string_literal: true

require "net/http"
require "openssl"
require "uri"

module Lingara
  # Raised into the token flight's own thread when one exchange attempt
  # outlives token_request_timeout: (D3). Being a Timeout::Error, it is one
  # Net::HTTP's own cleanup already handles.
  class TokenAttemptTimeout < Timeout::Error; end

  # One HTTP request per Net::HTTP.start, so leaving it by any path closes the
  # socket, and the mapping of what Net::HTTP raises onto TransportError kinds
  # (D3, D4). Only the classes named here are rescued: a caller's interrupt,
  # and an exception raised by a caller's own block, pass through untouched.
  class Transport
    # The settings the library owns, whatever net_http_options: says:
    # max_retries on every request, and read_timeout on a stream, where it
    # is the idle timeout (#request's read_timeout:). A JSON call keeps a
    # caller's read_timeout, or Net::HTTP's own 60 s.
    OWNED = %i[max_retries].freeze

    # The classes a request may fail with, in D4's order.
    TLS = [OpenSSL::SSL::SSLError].freeze
    CONNECT = [Net::OpenTimeout, SocketError, Errno::ECONNREFUSED, Errno::EHOSTUNREACH, Errno::ENETUNREACH].freeze
    TIMEOUT = [Net::ReadTimeout, Net::WriteTimeout, TokenAttemptTimeout].freeze
    OTHER = [SystemCallError, IOError, Net::HTTPBadResponse].freeze
    NAMED = (TLS + CONNECT + TIMEOUT + OTHER).freeze

    # What one request has got as far as, for the kind mapping: whether its
    # status line and headers are in, whether it is an open event stream,
    # and whether control is inside a caller's block.
    class Phase
      attr_accessor :headers_seen, :event_stream, :in_caller
      attr_reader :tag

      def initialize(tag)
        @tag = tag
        @headers_seen = false
        @event_stream = false
        @in_caller = false
      end

      # Leaves the request at once, closing the socket unread: Net::HTTP.start's
      # ensure runs and nothing drains the body (D3, the terminal event).
      def leave(value = nil)
        throw @tag, value
      end

      # Runs a caller's block; an exception from it is the caller's own.
      def in_caller_block
        @in_caller = true
        result = yield
        @in_caller = false
        result
      end
    end

    def initialize(net_http_options:)
      @options = net_http_options.transform_keys(&:to_sym).except(*OWNED)
    end

    # Sends one request and yields the response and its Phase once the headers
    # are in. Returns the block's value, or whatever Phase#leave was given.
    # +secrets+ are scrubbed from any error's cause; +on_start+ receives the
    # started Net::HTTP (EventStream#close finishes it).
    def request(method, url, headers, body: nil, read_timeout: nil, secrets: [], on_start: nil)
      uri = URI(url)
      phase = Phase.new(Object.new)
      catch(phase.tag) do
        Net::HTTP.start(uri.host, uri.port, **start_options(uri, read_timeout)) do |http|
          on_start&.call(http)
          # Net::HTTP#request returns the response, not the block's value.
          value = nil
          http.request(build(method, uri, headers, body)) do |response|
            phase.headers_seen = true
            value = yield response, phase
          end
          value
        end
      end
    rescue *NAMED => e
      raise if phase.in_caller
      raise_transport(Transport.kind(e, phase), e, secrets)
    end

    # D4's order; the first match wins.
    def self.kind(error, phase)
      case error
      when *TLS then (phase.event_stream && eof_like?(error)) ? :stream_ended_early : :tls
      when *CONNECT then :connect
      when *TIMEOUT then :timeout
      when Net::HTTPBadResponse then :malformed_response
      else
        if !phase.headers_seen then :connect
        elsif phase.event_stream && error.is_a?(EOFError) then :stream_ended_early
        else :reset
        end
      end
    end

    # OpenSSL 3 reports a peer that closes without close_notify as an
    # SSLError rather than an EOF; on an open stream that is C2 D6's EOF.
    def self.eof_like?(error)
      error.message.include?("unexpected eof")
    end

    private

    def start_options(uri, read_timeout)
      options = @options.merge(use_ssl: uri.scheme == "https", max_retries: 0)
      options[:read_timeout] = read_timeout if read_timeout
      options
    end

    def build(method, uri, headers, body)
      request = Net::HTTPGenericRequest.new(method, !body.nil?, method != "HEAD", uri, headers)
      request.body = body if body
      request
    end

    def raise_transport(kind, error, secrets)
      text = error.message.to_s
      if secrets.any? { |secret| secret && !secret.empty? && text.include?(secret) }
        raise TransportError.new(kind), cause: ScrubbedCause.new
      end
      raise TransportError.new(kind, "#{error.class}: #{text}")
    end
  end
end
