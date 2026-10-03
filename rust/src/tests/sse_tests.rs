use super::{Frame, SseParser};

// The body of CONTRACT.md's Example A (conformance case
// k5.vocab-split-frames), plus a CRLF frame and a trailing keepalive.
const EXAMPLE_A: &str = concat!(
    ": keepalive\n\n",
    "event: started\ndata: {\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\",\"framework\":\"HSK\",\"count\":2,\"ai_generated\":true}}\n",
    "\nevent: item\ndata: {\"word\":\"你好\",\"pronunciation\":\"nǐ hǎo\",\"translation\":\"hello\"}\n\n",
    "event: item\r\ndata: {\"word\":\"谢谢\",\"pronunciation\":\"xiè xie\",\"translation\":\"thank you\"}\r\n\r\n",
    ": keepalive\n\nevent: done\ndata: {}\n\n",
);

fn expected() -> Vec<Frame> {
    let frame = |event: &str, data: &str| Frame { event: event.into(), data: data.into(), id: String::new() };
    vec![
        frame("started", "{\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\",\"framework\":\"HSK\",\"count\":2,\"ai_generated\":true}}"),
        frame("item", "{\"word\":\"你好\",\"pronunciation\":\"nǐ hǎo\",\"translation\":\"hello\"}"),
        frame("item", "{\"word\":\"谢谢\",\"pronunciation\":\"xiè xie\",\"translation\":\"thank you\"}"),
        frame("done", "{}"),
    ]
}

fn parse(chunks: &[&[u8]]) -> Vec<Frame> {
    let mut parser = SseParser::new();
    let mut frames: Vec<Frame> = chunks.iter().flat_map(|c| parser.push(c)).collect();
    frames.extend(parser.finish());
    frames
}

fn parse_text(texts: &[&str]) -> Vec<Frame> {
    let chunks: Vec<&[u8]> = texts.iter().map(|t| t.as_bytes()).collect();
    parse(&chunks)
}

/// 29.9.26p AC1: every chunking of Example A, including splits inside a
/// UTF-8 character, yields the same frames.
#[test]
fn frames_do_not_depend_on_chunk_boundaries() {
    let bytes = EXAMPLE_A.as_bytes();
    let want = expected();
    assert_eq!(parse(&[bytes]), want);
    for i in 1..bytes.len() {
        assert_eq!(parse(&[&bytes[..i], &bytes[i..]]), want, "split at {i}");
    }
    for i in (1..bytes.len()).step_by(7) {
        for j in (i + 1..bytes.len()).step_by(11) {
            assert_eq!(parse(&[&bytes[..i], &bytes[i..j], &bytes[j..]]), want, "split at {i}, {j}");
        }
    }
    let one_at_a_time: Vec<&[u8]> = bytes.chunks(1).collect();
    assert_eq!(parse(&one_at_a_time), want);
}

/// 29.9.26p AC2: CRLF, CR and LF line endings, comments and multi-line
/// `data` parse per C2 D6.
#[test]
fn line_endings_comments_and_multiline_data() {
    let frame = vec![Frame { event: "phase".into(), data: "{\"a\":1}".into(), id: String::new() }];
    assert_eq!(parse_text(&["event: phase\ndata: {\"a\":1}\n\n"]), frame);
    assert_eq!(parse_text(&["event: phase\r\ndata: {\"a\":1}\r\n\r\n"]), frame);
    assert_eq!(parse_text(&["event: phase\rdata: {\"a\":1}\r\r"]), frame);
    // A CRLF split across two chunks is one line ending, not two.
    assert_eq!(parse_text(&["event: phase\r", "\ndata: {\"a\":1}\r", "\n\r", "\n"]), frame);
    // Comments are dropped; retry and unknown fields are ignored.
    assert_eq!(parse_text(&[": hello\nretry: 10\nfoo: bar\nevent: phase\ndata: {\"a\":1}\n\n"]), frame);
    // Data lines join with \n, and only one leading space is stripped.
    let joined = vec![Frame { event: "x".into(), data: "a\n b\nc".into(), id: String::new() }];
    assert_eq!(parse_text(&["event: x\ndata: a\ndata:  b\ndata:c\n\n"]), joined);
    // No event name is `message`; a frame with no data is dropped.
    let message = vec![Frame { event: "message".into(), data: "1".into(), id: String::new() }];
    assert_eq!(parse_text(&["data: 1\n\nevent: empty\n\n"]), message);
    // An undispatched frame at end of input is discarded.
    assert_eq!(parse_text(&["event: x\ndata: 1\n"]), vec![]);
}

/// CONTRACT.md K5, Parsing (ADR 30.9.26aa D7): `id` sets the last-event-id
/// buffer, which persists across frames until the next `id`, an `id`
/// holding U+0000 is ignored, and an empty `id` clears it.
#[test]
fn the_last_event_id_buffer_persists_across_frames() {
    let ids = |texts: &[&str]| parse_text(texts).into_iter().map(|f| f.id).collect::<Vec<_>>();
    assert_eq!(ids(&["id: c1\nevent: event\ndata: {}\n\nevent: error\ndata: {}\n\n"]), ["c1", "c1"]);
    assert_eq!(ids(&["id: c1\ndata: 1\n\nid: c\u{0}2\ndata: 2\n\nid: c3\ndata: 3\n\n"]), ["c1", "c1", "c3"]);
    assert_eq!(ids(&["id: c1\n\nid\ndata: 1\n\n"]), [""]);
    assert_eq!(ids(&["data: 1\n\n"]), [""]);
}
