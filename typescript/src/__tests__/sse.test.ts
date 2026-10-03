import { describe, expect, it } from "vitest";

import { SseParser, type Frame } from "../sse.js";

// The body of CONTRACT.md's Example A (conformance case
// k5.vocab-split-frames), as bytes.
const EXAMPLE_A = [
  ": keepalive\n\n",
  "event: started\ndata: {\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\",\"framework\":\"HSK\",\"count\":2,\"ai_generated\":true}}\n",
  "\nevent: item\ndata: {\"word\":\"你好\",\"pronunciation\":\"nǐ hǎo\",\"translation\":\"hello\"}\n\n",
  "event: item\r\ndata: {\"word\":\"谢谢\",\"pronunciation\":\"xiè xie\",\"translation\":\"thank you\"}\r\n\r\n",
  ": keepalive\n\nevent: done\ndata: {}\n\n",
].join("");

const EXPECTED: Frame[] = [
  { event: "started", data: "{\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\",\"framework\":\"HSK\",\"count\":2,\"ai_generated\":true}}" },
  { event: "item", data: "{\"word\":\"你好\",\"pronunciation\":\"nǐ hǎo\",\"translation\":\"hello\"}" },
  { event: "item", data: "{\"word\":\"谢谢\",\"pronunciation\":\"xiè xie\",\"translation\":\"thank you\"}" },
  { event: "done", data: "{}" },
];

/** Decodes chunked bytes the way the stream does, and parses them. */
function parseChunks(chunks: Uint8Array[]): Frame[] {
  const parser = new SseParser();
  const decoder = new TextDecoder("utf-8");
  const frames = chunks.flatMap((c) => parser.push(decoder.decode(c, { stream: true })));
  return [...frames, ...parser.push(decoder.decode()), ...parser.end()];
}

function parseText(...texts: string[]): Frame[] {
  const parser = new SseParser();
  return [...texts.flatMap((t) => parser.push(t)), ...parser.end()];
}

describe("SseParser", () => {
  /** 29.9.26o AC1: every chunking of Example A, including splits inside a UTF-8 character. */
  it("frames do not depend on chunk boundaries", () => {
    const bytes = new TextEncoder().encode(EXAMPLE_A);
    expect(parseChunks([bytes])).toEqual(EXPECTED);
    for (let i = 1; i < bytes.length; i++) {
      expect(parseChunks([bytes.slice(0, i), bytes.slice(i)])).toEqual(EXPECTED);
    }
    for (let i = 1; i < bytes.length; i += 7) {
      for (let j = i + 1; j < bytes.length; j += 11) {
        expect(parseChunks([bytes.slice(0, i), bytes.slice(i, j), bytes.slice(j)])).toEqual(EXPECTED);
      }
    }
    expect(parseChunks([...bytes].map((b) => Uint8Array.of(b)))).toEqual(EXPECTED);
  });

  /** 29.9.26o AC2: CRLF, CR and LF line endings, comments and multi-line data, per CONTRACT.md K5. */
  it("line endings comments and multi-line data", () => {
    const frame = { event: "phase", data: "{\"a\":1}" };
    expect(parseText("event: phase\ndata: {\"a\":1}\n\n")).toEqual([frame]);
    expect(parseText("event: phase\r\ndata: {\"a\":1}\r\n\r\n")).toEqual([frame]);
    expect(parseText("event: phase\rdata: {\"a\":1}\r\r")).toEqual([frame]);
    // A CRLF split across two chunks is one line ending, not two.
    expect(parseText("event: phase\r", "\ndata: {\"a\":1}\r", "\n\r", "\n")).toEqual([frame]);
    // Comments are dropped; retry and unknown fields are ignored.
    expect(parseText(": hello\nretry: 10\nfoo: bar\nevent: phase\ndata: {\"a\":1}\n\n")).toEqual([frame]);
    // Data lines join with \n, and only one leading space is stripped.
    expect(parseText("event: x\ndata: a\ndata:  b\ndata:c\n\n")).toEqual([{ event: "x", data: "a\n b\nc" }]);
    // No event name is `message`; a frame with no data is dropped.
    expect(parseText("data: 1\n\nevent: empty\n\n")).toEqual([{ event: "message", data: "1" }]);
    // An undispatched frame at end of input is discarded.
    expect(parseText("event: x\ndata: 1\n")).toEqual([]);
  });

  /** 30.9.26aa D7: `id` sets the last-event-id buffer, which persists across frames; an id with U+0000 is ignored. */
  it("id is recorded and persists across frames", () => {
    expect(parseText("id: 7\nevent: a\ndata: 1\n\nevent: b\ndata: 2\n\nid: 8\u0000\nevent: c\ndata: 3\n\nid\nevent: d\ndata: 4\n\n")).toEqual([
      { event: "a", data: "1", id: "7" },
      { event: "b", data: "2", id: "7" },
      { event: "c", data: "3", id: "7" },
      // An empty `id` empties the buffer.
      { event: "d", data: "4" },
    ]);
  });
});
