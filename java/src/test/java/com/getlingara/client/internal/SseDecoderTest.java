package com.getlingara.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseDecoderTest {
  /**
   * The body of CONTRACT.md's Example A (conformance case k5.vocab-split-frames), plus a CRLF frame
   * and a trailing keepalive.
   */
  static final String EXAMPLE_A =
      ": keepalive\n\n"
          + "event: started\ndata: {\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\","
          + "\"framework\":\"HSK\",\"count\":2,\"ai_generated\":true}}\n"
          + "\nevent: item\ndata: {\"word\":\"你好\",\"pronunciation\":\"nǐ hǎo\",\"translation\":\"hello\"}\n\n"
          + "event: item\r\ndata: {\"word\":\"谢谢\",\"pronunciation\":\"xiè xie\","
          + "\"translation\":\"thank you\"}\r\n\r\n"
          + ": keepalive\n\nevent: done\ndata: {}\n\n";

  static final List<SseDecoder.Frame> EXAMPLE_FRAMES =
      List.of(
          new SseDecoder.Frame(
              "started",
              "{\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\","
                  + "\"framework\":\"HSK\",\"count\":2,\"ai_generated\":true}}"),
          new SseDecoder.Frame(
              "item", "{\"word\":\"你好\",\"pronunciation\":\"nǐ hǎo\",\"translation\":\"hello\"}"),
          new SseDecoder.Frame(
              "item",
              "{\"word\":\"谢谢\",\"pronunciation\":\"xiè xie\",\"translation\":\"thank you\"}"),
          new SseDecoder.Frame("done", "{}"));

  private static List<SseDecoder.Frame> decode(byte[]... chunks) {
    SseDecoder decoder = new SseDecoder();
    List<SseDecoder.Frame> frames = new ArrayList<>();
    for (byte[] chunk : chunks) {
      frames.addAll(decoder.feed(chunk, 0, chunk.length));
    }
    return frames;
  }

  private static List<SseDecoder.Frame> decode(String... chunks) {
    return decode(
        Arrays.stream(chunks).map(c -> c.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new));
  }

  /**
   * 29.9.26r AC1: every two-piece split of Example A, every three-piece split whose cuts are at
   * most eight bytes apart (enough to cut each multi-byte character twice), and one byte at a time
   * yield the same frames, including a {@code \r\n} split across two reads.
   */
  @Test
  void framesDoNotDependOnChunkBoundaries() {
    byte[] b = EXAMPLE_A.getBytes(StandardCharsets.UTF_8);
    assertEquals(EXAMPLE_FRAMES, decode(b));
    for (int i = 0; i <= b.length; i++) {
      byte[] head = Arrays.copyOfRange(b, 0, i);
      assertEquals(EXAMPLE_FRAMES, decode(head, Arrays.copyOfRange(b, i, b.length)), "at " + i);
      for (int j = i; j <= Math.min(i + 8, b.length); j++) {
        byte[] middle = Arrays.copyOfRange(b, i, j);
        byte[] tail = Arrays.copyOfRange(b, j, b.length);
        assertEquals(EXAMPLE_FRAMES, decode(head, middle, tail), "at " + i + ", " + j);
      }
    }
    byte[][] single = new byte[b.length][];
    for (int i = 0; i < b.length; i++) {
      single[i] = new byte[] {b[i]};
    }
    assertEquals(EXAMPLE_FRAMES, decode(single));
    assertEquals(
        List.of(new SseDecoder.Frame("a", "1")), decode("event: a\r", "\ndata: 1\r", "\n\r", "\n"));
  }

  /** 29.9.26r AC2: CRLF, CR and LF line endings, comments and multi-line data parse per C2 D6. */
  @Test
  void lineEndingsCommentsAndMultiLineData() {
    List<SseDecoder.Frame> one = List.of(new SseDecoder.Frame("a", "1"));
    assertEquals(one, decode("event: a\ndata: 1\n\n"));
    assertEquals(one, decode("event: a\r\ndata: 1\r\n\r\n"));
    assertEquals(one, decode("event: a\rdata: 1\r\r"));
    assertEquals(one, decode(": comment\nevent: a\n: another\ndata: 1\n\n"));
    assertEquals(
        List.of(new SseDecoder.Frame("a", "line one\nline two")),
        decode("event: a\ndata: line one\ndata: line two\n\n"));
    assertEquals(List.of(new SseDecoder.Frame("message", "x")), decode("data: x\n\n"));
    assertEquals(List.of(), decode("event: a\n\n"), "a frame with no data is dropped");
    assertEquals(
        List.of(new SseDecoder.Frame("a", " two spaces", "7")),
        decode("event:a\nid: 7\nretry: 10\nunknown: y\ndata:  two spaces\n\n"));
    assertEquals(List.of(), decode("event: a\ndata: 1\n"), "no blank line, no dispatch");
  }

  /**
   * 30.9.26aa D7: {@code id} sets the last-event-id buffer, which persists across frames until the
   * next {@code id}, and an {@code id} containing U+0000 is ignored.
   */
  @Test
  void idSetsALastEventIdBufferThatPersists() {
    assertEquals(
        List.of(
            new SseDecoder.Frame("event", "1", "c1"),
            new SseDecoder.Frame("error", "2", "c1"),
            new SseDecoder.Frame("done", "3", "h1"),
            new SseDecoder.Frame("done", "4", "h1")),
        decode(
            "id: c1\nevent: event\ndata: 1\n\n",
            "event: error\ndata: 2\n\n",
            "id: h1\nevent: done\ndata: 3\n\n",
            "id: h\u00002\nevent: done\ndata: 4\n\n"));
  }
}
