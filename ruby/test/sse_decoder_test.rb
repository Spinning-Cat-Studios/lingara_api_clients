# frozen_string_literal: true

require "support/fake_server"

class SSEDecoderTest < Minitest::Test
  # C2 Example A: the vocabulary stream, with a keepalive and a multi-byte
  # word, as the server sends it.
  EXAMPLE_A = "event: started\ndata: {\"meta\":{\"level\":2}}\n\n" \
    ": keepalive\n\n" \
    "event: item\r\ndata: {\"word\":\"你好\",\"translation\":\"hello\"}\r\n\r\n" \
    "event: done\ndata: {}\n\n"

  def decode(chunks)
    decoder = Lingara::SSEDecoder.new
    chunks.flat_map { |chunk| decoder.feed(chunk) }.map(&:to_a)
  end

  # 29.9.26t AC5: the same frames for every chunking of Example A's bytes,
  # including a split inside a UTF-8 character and a \r\n split across reads.
  def test_decodes_identically_however_chunked
    bytes = EXAMPLE_A.b
    whole = decode([bytes])
    assert_equal [["started", "{\"meta\":{\"level\":2}}"], ["item", "{\"word\":\"你好\",\"translation\":\"hello\"}"], ["done", "{}"]], whole
    (1...bytes.bytesize).each do |at|
      assert_equal whole, decode([bytes.byteslice(0, at), bytes.byteslice(at..)]), "split at byte #{at}"
    end
    assert_equal whole, decode(bytes.each_char.to_a), "one byte at a time"
    inside = bytes.index("你".b) + 1
    assert_equal whole, decode([bytes.byteslice(0, inside), bytes.byteslice(inside..)])
    crlf = bytes.index("\r\n".b) + 1
    assert_equal whole, decode([bytes.byteslice(0, crlf), bytes.byteslice(crlf..)])
  end

  # 29.9.26t AC6: CRLF, CR and LF endings, comments, and multi-line data.
  def test_line_endings_comments_and_multi_line_data
    frames = decode(["event: a\rdata: one\r\r", ": comment\n", "event: b\r\ndata: x\r\ndata:  y\r\nid: 7\r\nretry: 5\r\n\r\n",
      "data: nameless\n\n", "event: empty\n\n", "data\n\n"])
    assert_equal [["a", "one"], ["b", "x\n y"], ["message", "nameless"], ["message", ""]], frames
  end

  def test_finish_drops_an_undispatched_frame
    decoder = Lingara::SSEDecoder.new
    assert_empty decoder.feed("event: a\ndata: x\n")
    assert_empty decoder.finish
  end
end
