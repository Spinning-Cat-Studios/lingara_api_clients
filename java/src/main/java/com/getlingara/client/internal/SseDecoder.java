package com.getlingara.client.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The server-sent-events decoder: bytes in, frames out, no I/O (CONTRACT.md K5, Parsing).
 *
 * <p>It splits lines on bytes, at {@code \r\n}, {@code \n} or {@code \r}. Every line ending is
 * ASCII, so a UTF-8 character split across reads is carried whole in the line buffer and decoded
 * only once its line ends. A {@code \r} that ends one read is remembered, so a {@code \n} opening
 * the next is the same line ending rather than a blank line that would dispatch early. The frames
 * are therefore the same however the bytes were chunked, down to one byte at a time.
 *
 * <p>{@code id} sets the last-event-id buffer, which persists across frames until the next {@code
 * id} field, as WHATWG defines it; an {@code id} containing U+0000 is ignored. Every frame carries
 * the buffer as it stood when the frame was dispatched (ADR 30.9.26aa D7).
 */
public final class SseDecoder {
  /**
   * One dispatched frame.
   *
   * @param event the event name, {@code message} when the frame named none
   * @param data the data lines, joined by {@code \n}
   * @param id the last-event-id buffer, empty until an {@code id} field sets it
   */
  public record Frame(String event, String data, String id) {
    /**
     * A frame with an empty last-event-id buffer.
     *
     * @param event the event name
     * @param data the data lines
     */
    public Frame(String event, String data) {
      this(event, data, "");
    }
  }

  private final ByteArrayOutputStream line = new ByteArrayOutputStream();
  private final List<String> data = new ArrayList<>();
  private String event = "";
  private String lastEventId = "";
  private boolean skipLineFeed;

  /** A decoder with nothing buffered. */
  public SseDecoder() {}

  /**
   * Consumes bytes and returns every frame they completed.
   *
   * @param bytes the buffer
   * @param offset where the bytes start
   * @param length how many bytes to read
   * @return the completed frames, in order
   */
  public List<Frame> feed(byte[] bytes, int offset, int length) {
    List<Frame> frames = new ArrayList<>();
    for (int i = offset; i < offset + length; i++) {
      byte b = bytes[i];
      boolean lineFeedAfterReturn = skipLineFeed && b == '\n';
      skipLineFeed = false;
      if (lineFeedAfterReturn) {
        continue;
      }
      if (b == '\n' || b == '\r') {
        endLine(frames);
        skipLineFeed = b == '\r';
      } else {
        line.write(b);
      }
    }
    return frames;
  }

  private void endLine(List<Frame> frames) {
    String text = line.toString(StandardCharsets.UTF_8);
    line.reset();
    if (text.isEmpty()) {
      dispatch(frames);
      return;
    }
    if (text.startsWith(":")) {
      return;
    }
    int colon = text.indexOf(':');
    String field = colon < 0 ? text : text.substring(0, colon);
    String value = colon < 0 ? "" : text.substring(colon + 1);
    if (value.startsWith(" ")) {
      value = value.substring(1);
    }
    if (field.equals("event")) {
      event = value;
    } else if (field.equals("data")) {
      data.add(value);
    } else if (field.equals("id") && value.indexOf('\u0000') < 0) {
      lastEventId = value;
    }
    // retry and unknown fields are ignored.
  }

  /** Ends a frame on a blank line: one with no data is dropped, one with no event is "message". */
  private void dispatch(List<Frame> frames) {
    if (!data.isEmpty()) {
      String name = event.isEmpty() ? "message" : event;
      frames.add(new Frame(name, String.join("\n", data), lastEventId));
    }
    event = "";
    data.clear();
  }
}
