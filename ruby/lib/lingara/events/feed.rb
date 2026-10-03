# frozen_string_literal: true

module Lingara
  module Events
    # The feed helper (ADR 30.9.26aa D6; CONTRACT.md, The event helpers): it
    # walks listEvents' pages and yields each item as an Event, parsed from
    # the item's own JSON. #cursor is where to resume: after a page's last
    # item, that page's next_cursor, which advances even on an empty page.
    # It ends on a page with has_more false and never sleeps or polls; a
    # later #each resumes from #cursor. A known type whose data does not
    # decode raises TransportError :malformed_event, and every refusal its
    # K3 error, 410 cursor_expired included.
    class Feed
      include Enumerable

      attr_reader :cursor

      # +fetch+ is the client's: it sends one listEvents request for a query
      # and returns the page as a Hash.
      def initialize(fetch:, cursor:, start:, types:)
        @fetch = fetch
        @cursor = cursor
        @start = start
        @types = types
      end

      # Yields each event to the horizon, then returns self. Without a
      # block, an Enumerator.
      def each
        return enum_for(:each) unless block_given?
        loop do
          # start only without a cursor, the server's own precedence, so the
          # helper never earns the 400 the pair draws.
          page = @fetch.call(@cursor ? {cursor: @cursor, types: @types} : {start: @start, types: @types})
          page["items"].each { |item| yield Events.decode(item) }
          @cursor = page["next_cursor"]
          break unless page["has_more"]
        end
        self
      end

      def inspect
        "#<Lingara::Events::Feed cursor=#{@cursor.inspect}>"
      end
    end
  end
end
