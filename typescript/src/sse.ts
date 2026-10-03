// The server-sent-events parser: text in, frames out, no I/O (CONTRACT.md
// K5, Parsing; the `id` field since ADR 30.9.26aa D7). The stream owns the bytes and the UTF-8 decoding; this owns
// line endings, comments, fields and dispatch, so a test can drive it one
// character at a time.

/**
 * One dispatched frame: its event name, its joined `data`, and the
 * last-event-id buffer when it is not empty.
 */
export interface Frame {
  event: string;
  data: string;
  id?: string;
}

export class SseParser {
  #buffer = "";
  #event = "";
  #data: string[] = [];
  #hasData = false;
  // WHATWG's last-event-id buffer: it persists across frames until the next
  // `id` field, and is not reset on dispatch.
  #lastEventId = "";
  // A chunk ended on `\r`: a `\n` opening the next chunk is the same line end.
  #skipLf = false;

  /** Feeds decoded text; returns every frame it completed. */
  push(text: string): Frame[] {
    let input = text;
    if (this.#skipLf && input.length > 0) {
      if (input.startsWith("\n")) input = input.slice(1);
      this.#skipLf = false;
    }
    const frames: Frame[] = [];
    const s = this.#buffer + input;
    let start = 0;
    for (;;) {
      const end = lineEnd(s, start);
      if (end < 0) break;
      this.#line(s.slice(start, end), frames);
      start = this.#afterEnding(s, end);
    }
    this.#buffer = s.slice(start);
    return frames;
  }

  /** End of input: an undispatched frame is discarded, as WHATWG says. */
  end(): Frame[] {
    this.#buffer = "";
    this.#reset();
    return [];
  }

  #afterEnding(s: string, end: number): number {
    if (s[end] === "\n") return end + 1;
    if (end + 1 < s.length) return s[end + 1] === "\n" ? end + 2 : end + 1;
    this.#skipLf = true;
    return end + 1;
  }

  #line(line: string, frames: Frame[]): void {
    if (line === "") {
      if (this.#hasData) frames.push(this.#frame());
      this.#reset();
      return;
    }
    if (line.startsWith(":")) return;
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    this.#field(field, value);
  }

  #field(field: string, value: string): void {
    if (field === "event") this.#event = value;
    if (field === "data") {
      this.#data.push(value);
      this.#hasData = true;
    }
    if (field === "id" && !value.includes("\0")) this.#lastEventId = value;
  }

  #frame(): Frame {
    const frame: Frame = { event: this.#event || "message", data: this.#data.join("\n") };
    if (this.#lastEventId !== "") frame.id = this.#lastEventId;
    return frame;
  }

  #reset(): void {
    this.#event = "";
    this.#data = [];
    this.#hasData = false;
  }
}

function lineEnd(s: string, from: number): number {
  for (let i = from; i < s.length; i++) {
    const c = s[i];
    if (c === "\n" || c === "\r") return i;
  }
  return -1;
}
