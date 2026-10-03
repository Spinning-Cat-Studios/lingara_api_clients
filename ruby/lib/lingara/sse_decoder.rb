# frozen_string_literal: true

module Lingara
  # K5's frame parser (CONTRACT.md K5, Parsing): pure, with no I/O. It
  # buffers bytes, splits lines at \r\n, \n or \r (holding a trailing \r
  # until the next byte says whether a \n follows), and decodes UTF-8 only per
  # complete line, so a character split across chunks is never decoded half.
  # The frames it returns are the same however the bytes were chunked.
  #
  # It records `id` as WHATWG's last-event-id buffer (ADR 30.9.26aa D7): an
  # `id` field sets it unless its value contains U+0000, and it persists
  # across frames until the next `id` field. Only the tail (K5a) reads it.
  class SSEDecoder
    # One dispatched frame: its event name (`message` when none was sent),
    # its data lines joined by \n, and the last-event-id buffer when it was
    # dispatched (nil until an `id` field has been seen).
    Frame = Struct.new(:event, :data, :id)

    CR = "\r".b.freeze
    LF = "\n".b.freeze

    def initialize
      @buffer = +"".b
      @event = nil
      @data = nil
      @last_event_id = nil
    end

    # Feeds bytes and returns every frame they complete.
    def feed(bytes)
      @buffer << bytes.b
      frames = []
      while (line = next_line)
        frame = take_line(line)
        frames << frame if frame
      end
      frames
    end

    # The end of the stream: a partial line is dropped, as is any frame with
    # no blank line after it. Nothing further is dispatched.
    def finish
      @buffer.clear
      @event = nil
      @data = nil
      []
    end

    private

    # The next complete line, its terminator removed, or nil. A \r at the
    # very end of the buffer is held until the next byte arrives.
    def next_line
      index = @buffer.index(/[\r\n]/n)
      return nil unless index
      if @buffer.getbyte(index) == 13
        return nil if index == @buffer.bytesize - 1
        length = (@buffer.getbyte(index + 1) == 10) ? 2 : 1
      else
        length = 1
      end
      line = @buffer.byteslice(0, index)
      @buffer = @buffer.byteslice(index + length, @buffer.bytesize - index - length)
      line.force_encoding(Encoding::UTF_8).scrub
    end

    def take_line(line)
      return dispatch if line.empty?
      return nil if line.start_with?(":")
      field, value = line.split(":", 2)
      value = value&.delete_prefix(" ") || ""
      case field
      when "event" then @event = value
      when "data" then @data = @data ? "#{@data}\n#{value}" : value
      when "id" then @last_event_id = value unless value.include?("\u0000")
      end
      nil
    end

    def dispatch
      frame = @data && Frame.new(@event || "message", @data, @last_event_id)
      @event = nil
      @data = nil
      frame
    end
  end
end
