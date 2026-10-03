# frozen_string_literal: true

module Lingara
  module Events
    # The tail helper (CONTRACT.md K5a; ADR 30.9.26aa D7): streamEvents,
    # reopened after every ending, so it never ends on its own. Leave the
    # block (break, return or an exception) to stop it, at any point,
    # including a reconnect sleep.
    #
    # #cursor is the id of the last frame that carried one, an event or a
    # done, and every reopen sends it as Last-Event-ID. A done reopens at
    # once. An error event, EOF and every TransportError are failures, the
    # first open included: the sleeper is handed 1, 2, 4 … 30 s between
    # them, and the max_failures-th consecutive one is raised. The count
    # resets on a connection's first event or done frame, never on its 200.
    # A tail open bypasses K4: a 429 or 503 is one failure whose Retry-After
    # (within retry_after_cap) replaces that step's delay; 401 gets K1's one
    # refresh; every other refusal, and a known type whose data does not
    # decode, is raised at once.
    class Tail
      include Enumerable

      # Why one connection ended, and the delay that replaces the backoff's.
      Failure = Struct.new(:error, :delay)

      MAX_DELAY = 30

      attr_reader :cursor

      # +open+ is the client's: it sends one streamEvents request, with
      # Last-Event-ID when given one, and yields the 2xx response.
      def initialize(open:, cursor:, max_failures:, policy:)
        @open = open
        @cursor = cursor
        @max_failures = max_failures
        @policy = policy
        @failures = 0
      end

      # Yields each event until the block is left or the failures are spent.
      # Without a block, an Enumerator.
      def each(&block)
        return enum_for(:each) unless block
        @failures = 0
        loop do
          failure = connect(&block)
          next if failure.nil?
          @failures += 1
          raise failure.error if @failures >= @max_failures
          @policy.sleeper.call(failure.delay || [2**(@failures - 1), MAX_DELAY].min)
        end
      end

      def inspect
        "#<Lingara::Events::Tail cursor=#{@cursor.inspect}>"
      end

      private

      # One connection: nil after a done, else its Failure.
      def connect(&block)
        @open.call(@cursor, nil) { |response, phase, observe| consume(response, phase, observe, &block) }
      rescue TransportError => e
        raise if e.kind == :malformed_event
        Failure.new(e, nil)
      rescue ApiError, MaintenanceError => e
        busy(e)
      end

      # A 429 or 503 is a failure; any other refusal, and a Retry-After past
      # the cap, is raised.
      def busy(error)
        status = error.is_a?(MaintenanceError) ? 503 : error.status
        raise error unless [429, 503].include?(status)
        raise error if error.retry_after && error.retry_after > @policy.retry_after_cap
        Failure.new(error, error.retry_after)
      end

      def consume(response, phase, observe, &block)
        unless Refusal.media_type(response) == "text/event-stream"
          raise TransportError.new(:malformed_response, "a 200 stream answered #{Refusal.media_type(response).inspect}")
        end
        served = observe.call(response)
        phase.event_stream = true
        decoder = SSEDecoder.new
        response.read_body do |chunk|
          decoder.feed(chunk).each { |frame| handle(frame, phase, served, &block) }
        end
        Failure.new(TransportError.new(:stream_ended_early, "the tail closed before its ending event"), nil)
      end

      def handle(frame, phase, served, &block)
        case frame.event
        when "event"
          event = Events.parse(frame.data)
          advance(frame)
          phase.in_caller_block { block.call(event) }
        when "done"
          advance(frame)
          phase.leave(nil)
        when "error"
          phase.leave(Failure.new(Refusal.stream_error(frame.data, served), nil))
        end
      end

      def advance(frame)
        @failures = 0
        @cursor = frame.id if frame.id && !frame.id.empty?
      end
    end
  end
end
