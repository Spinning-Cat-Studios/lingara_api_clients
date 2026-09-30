# frozen_string_literal: true

require "json"

module Lingara
  # K5: one stream of events (CONTRACT.md K5; D3).
  #
  # A stream call with a block sends at once, yields each event and returns a
  # StreamResult. Without one it returns an EventStream, which sends nothing
  # until its first #each or #next, and is single-use: a second #each raises
  # IOError, as reading a closed IO does. Leaving the block by any path, or
  # #close, closes the socket. The terminal table is the view's, generated
  # into operations.rb (ADR 29.9.26ai): :raise, :quiet and :yield.
  class EventStream
    include Enumerable

    # The Lingara-Version echo, or nil; nil until the first #each or #next.
    attr_reader :served_version

    # +pipeline+ is the client's: it sends the request, with its token, 401
    # and Retry-After retries, and yields the 2xx response.
    def initialize(pipeline:, operation:, url:, body:)
      @pipeline = pipeline
      @operation = operation
      @url = url
      @body = body
      @mutex = Mutex.new
      @consumed = false
      @http = nil
      @external = nil
      @served_version = nil
    end

    # Yields each event, then returns self. Without a block, an Enumerator.
    def each(&block)
      return enum_for(:each) unless block
      claim!
      run(&block)
      self
    end

    # External iteration: the next event, or StopIteration after the last.
    # The socket stays open between calls, so call #close when done early.
    def next
      @mutex.synchronize do
        unless @external
          raise IOError, "stream already consumed" if @consumed
          @consumed = true
          @external = Enumerator.new { |y| run { |event| y << event } }
        end
      end
      @external.next
    end

    # Closes the socket if a request is open, and marks the stream consumed.
    # Idempotent. An abandoned enumerator fiber never runs its ensure, so this
    # finishes the Net::HTTP session itself rather than relying on one.
    def close
      http = nil
      @mutex.synchronize do
        @consumed = true
        @external = nil
        http, @http = @http, nil
      end
      http.finish if http&.started?
      nil
    rescue IOError
      nil
    end

    # Sends the request and yields each event; returns the StreamResult.
    # Used directly by the block form of a stream call.
    def run(&block)
      @pipeline.call(@operation, @url, @body, on_start: ->(http) { @http = http }) do |response, phase, observe|
        consume(response, phase, observe, &block)
      end
    end

    private

    def claim!
      @mutex.synchronize do
        raise IOError, "stream already consumed" if @consumed
        @consumed = true
      end
    end

    def consume(response, phase, observe, &block)
      unless Refusal.media_type(response) == "text/event-stream"
        raise TransportError.new(:malformed_response, "a 200 stream answered #{Refusal.media_type(response).inspect}")
      end
      # The deprecation hook runs after the headers, before the first event.
      @served_version = observe.call(response)
      phase.event_stream = true
      decoder = SSEDecoder.new
      response.read_body do |chunk|
        decoder.feed(chunk).each { |frame| handle(frame, phase, &block) }
      end
      raise TransportError.new(:stream_ended_early, "the stream closed before its terminal event")
    end

    def handle(frame, phase, &block)
      stream = @operation[:stream]
      return unless stream[:events].key?(frame.event)
      ending = stream[:ends][frame.event]
      raise stream_error(frame.data) if ending == :raise
      return finish(phase, frame) if ending == :quiet
      event = Lingara.const_get(stream[:union]).decode(frame.event, frame.data)
      return if event.nil?
      phase.in_caller_block { block.call(event) }
      phase.leave(StreamResult.new(served_version: @served_version)) if ending == :yield
    end

    # A Done payload: never yielded, but still the JSON it should be.
    def finish(phase, frame)
      JSON.parse(frame.data)
      phase.leave(StreamResult.new(served_version: @served_version))
    rescue JSON::ParserError
      raise TransportError.new(:malformed_event, "#{frame.event}: data is not JSON")
    end

    # An error event: ApiError with status 200, never yielded or retried.
    def stream_error(data)
      fields = begin
        parsed = JSON.parse(data)
        parsed.is_a?(Hash) ? parsed : {}
      rescue JSON::ParserError
        {}
      end
      text = ->(key, fallback) { fields[key].is_a?(String) ? fields[key] : fallback }
      ApiError.new(status: 200, code: text.call("code", "stream_error"), message: text.call("message", "the stream reported an error"),
        plan_id: text.call("plan_id", nil), served_version: @served_version)
    end
  end
end
