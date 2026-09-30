use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::{Duration, Instant};

use tokio::io::{AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::case::Chunk;
use crate::control;
use crate::replay::{LAST_CHUNK, frame, write_chunks};
use crate::test_support::{UA, loaded, raw, start};

/// Records each flushed write as its own segment.
#[derive(Default)]
struct Recorder {
    pending: Vec<u8>,
    writes: Vec<Vec<u8>>,
}

impl AsyncWrite for Recorder {
    fn poll_write(mut self: Pin<&mut Self>, _: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        self.pending.extend_from_slice(buf);
        Poll::Ready(Ok(buf.len()))
    }

    fn poll_flush(mut self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
        let segment = std::mem::take(&mut self.pending);
        self.writes.push(segment);
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}

fn stream_case(then: &str) -> crate::case::Loaded {
    let yaml = format!(
        "
id: k5.held
title: t
behaviours: [K5]
steps:
  - call: {{ operation: generateVocabulary, cancel_after_events: 1 }}
    expect: {{ outcome: cancelled }}
exchanges:
  items:
    - request: {{ method: POST, path: /v1/vocab/stream }}
      response:
        status: 200
        sse:
          chunks:
            - \"event: started\\ndata: {{}}\\n\\n\"
          {then}
"
    );
    loaded("k5/held.yaml", &yaml)
}

async fn open_stream(port: u16) -> TcpStream {
    let mut stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    let request = raw("POST", "/v1/vocab/stream", &[("user-agent", UA)]);
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut seen = Vec::new();
    let mut buf = [0u8; 1024];
    while !String::from_utf8_lossy(&seen).contains("event: started") {
        let n = stream.read(&mut buf).await.unwrap();
        assert!(n > 0, "closed before the first event");
        seen.extend_from_slice(&buf[..n]);
    }
    stream
}

/// 29.9.26n AC9: each `chunks` entry is its own write, `hex` is raw bytes,
/// and `after_ms` pauses without writing. Since 29.9.26o each write is one
/// HTTP/1.1 chunk, and an empty entry writes nothing.
#[tokio::test]
async fn chunks_are_separate_writes_including_hex() {
    let chunks: Vec<Chunk> = serde_yaml::from_str("[ 'data: \"', { hex: e4bd }, { after_ms: 30 }, { hex: a0 }, '', '\"' ]").unwrap();
    let mut recorder = Recorder::default();
    let started = Instant::now();
    write_chunks(&mut recorder, &chunks).await.unwrap();
    assert!(started.elapsed() >= Duration::from_millis(30));
    let payloads: Vec<Vec<u8>> = vec![b"data: \"".to_vec(), vec![0xe4, 0xbd], vec![0xa0], b"\"".to_vec()];
    let expected: Vec<Vec<u8>> = payloads.iter().map(|p| frame(p)).collect();
    assert_eq!(recorder.writes, expected);
    assert_eq!(recorder.writes[1], b"2\r\n\xe4\xbd\r\n".to_vec());
    let joined: Vec<u8> = payloads.concat();
    assert_eq!(String::from_utf8(joined).unwrap(), "data: \"你\"");
}

/// 29.9.26o: a stream is chunked, and only `close` sends the last chunk,
/// so a reset is a body with no end marker on every client.
#[tokio::test]
async fn close_ends_the_chunked_body_and_reset_does_not() {
    let (port, shared) = start(vec![stream_case("then: close")]).await;
    control::arm(&shared, "k5.held");
    let mut stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    let request = raw("POST", "/v1/vocab/stream", &[("user-agent", UA)]);
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut all = Vec::new();
    stream.read_to_end(&mut all).await.unwrap();
    let text = String::from_utf8_lossy(&all);
    assert!(text.contains("transfer-encoding: chunked\r\n"), "{text}");
    assert!(all.ends_with(LAST_CHUNK), "{text}");
}

/// 29.9.26n AC10: a `hold` the client never lets go of is a mismatch.
#[tokio::test]
async fn hold_without_disconnect_is_a_mismatch() {
    let (port, shared) = start(vec![stream_case("then: hold\n          disconnect_within_ms: 100")]).await;
    control::arm(&shared, "k5.held");
    let stream = open_stream(port).await;
    let verdict = control::finish(&shared, "k5.held").await.1;
    assert_eq!(verdict["pass"], false, "{verdict}");
    assert!(verdict["mismatches"].to_string().contains("did not disconnect within 100 ms"));
    drop(stream);
}

#[tokio::test]
async fn hold_with_prompt_disconnect_passes() {
    let (port, shared) = start(vec![stream_case("then: hold\n          disconnect_within_ms: 2000")]).await;
    control::arm(&shared, "k5.held");
    drop(open_stream(port).await);
    let verdict = control::finish(&shared, "k5.held").await.1;
    assert_eq!(verdict["pass"], true, "{verdict}");
}

/// 29.9.26n AC19: `then: reset` is a TCP reset, which a reader sees as an
/// error rather than a clean end of stream.
#[tokio::test]
async fn reset_is_not_a_clean_close() {
    let (port, shared) = start(vec![stream_case("then: reset")]).await;
    control::arm(&shared, "k5.held");
    let mut stream = open_stream(port).await;
    let mut buf = [0u8; 1024];
    let ended = loop {
        match stream.read(&mut buf).await {
            Ok(0) => break Ok(()),
            Ok(_) => continue,
            Err(e) => break Err(e.kind()),
        }
    };
    assert_eq!(ended, Err(io::ErrorKind::ConnectionReset));
    let clean = start(vec![stream_case("then: close")]).await;
    control::arm(&clean.1, "k5.held");
    let mut stream = open_stream(clean.0).await;
    let mut rest = Vec::new();
    stream.read_to_end(&mut rest).await.expect("close is a clean EOF");
}
