//! Test support: a scripted HTTP/1.1 listener on `127.0.0.1:0`, a virtual
//! clock and a recording sleeper. No network beyond loopback, no TLS.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;

use crate::BoxFuture;
use crate::seams::{Clock, Sleeper};

/// One request the server read.
#[derive(Clone, Debug)]
pub struct Recorded {
    pub method: String,
    pub path: String,
    pub headers: Vec<(String, String)>,
    pub body: String,
}

impl Recorded {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(n, _)| n == name).map(|(_, v)| v.as_str())
    }
}

/// What to write back: raw bytes after an optional delay. A held reply
/// keeps the connection open until the client closes it.
pub struct Reply {
    pub delay: Duration,
    pub bytes: Vec<u8>,
    pub hold: bool,
}

impl Reply {
    pub fn json(status: u16, body: &str) -> Self {
        Self::with(status, &[("content-type", "application/json")], body)
    }

    pub fn with(status: u16, headers: &[(&str, &str)], body: &str) -> Self {
        let mut head = format!("HTTP/1.1 {status} X\r\ncontent-length: {}\r\nconnection: close\r\n", body.len());
        for (name, value) in headers {
            head.push_str(&format!("{name}: {value}\r\n"));
        }
        Self { delay: Duration::ZERO, bytes: format!("{head}\r\n{body}").into_bytes(), hold: false }
    }

    /// A chunked `text/event-stream`, each string one chunk. Unless held,
    /// it ends with the last chunk.
    pub fn sse(headers: &[(&str, &str)], chunks: &[&str], hold: bool) -> Self {
        let mut out = String::from("HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\ntransfer-encoding: chunked\r\n");
        for (name, value) in headers {
            out.push_str(&format!("{name}: {value}\r\n"));
        }
        out.push_str("\r\n");
        for chunk in chunks {
            out.push_str(&format!("{:x}\r\n{chunk}\r\n", chunk.len()));
        }
        if !hold {
            out.push_str("0\r\n\r\n");
        }
        Self { delay: Duration::ZERO, bytes: out.into_bytes(), hold }
    }

    pub fn after(mut self, delay: Duration) -> Self {
        self.delay = delay;
        self
    }
}

type Respond = dyn Fn(&Recorded, usize) -> Reply + Send + Sync;

pub struct FakeServer {
    pub url: String,
    requests: Arc<Mutex<Vec<Recorded>>>,
    disconnects: mpsc::UnboundedReceiver<()>,
}

impl FakeServer {
    /// Answers the n-th request (from 0) with `respond(request, n)`.
    pub async fn start(respond: impl Fn(&Recorded, usize) -> Reply + Send + Sync + 'static) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let requests = Arc::new(Mutex::new(Vec::new()));
        let (tx, disconnects) = mpsc::unbounded_channel();
        let (log, respond, count) = (Arc::clone(&requests), Arc::new(respond) as Arc<Respond>, Arc::new(AtomicUsize::new(0)));
        tokio::spawn(async move {
            while let Ok((socket, _)) = listener.accept().await {
                let (log, respond, count, tx) = (Arc::clone(&log), Arc::clone(&respond), Arc::clone(&count), tx.clone());
                tokio::spawn(async move { serve(socket, &log, &*respond, &count, &tx).await });
            }
        });
        Self { url, requests, disconnects }
    }

    pub fn requests(&self) -> Vec<Recorded> {
        self.requests.lock().unwrap().clone()
    }

    /// Whether a held connection saw the client close within `limit`.
    pub async fn disconnected_within(&mut self, limit: Duration) -> bool {
        matches!(tokio::time::timeout(limit, self.disconnects.recv()).await, Ok(Some(())))
    }
}

async fn serve(mut socket: TcpStream, log: &Mutex<Vec<Recorded>>, respond: &Respond, count: &AtomicUsize, tx: &mpsc::UnboundedSender<()>) {
    let Some(request) = read_request(&mut socket).await else { return };
    log.lock().unwrap().push(request.clone());
    let reply = respond(&request, count.fetch_add(1, Ordering::SeqCst));
    tokio::time::sleep(reply.delay).await;
    if socket.write_all(&reply.bytes).await.is_err() {
        return;
    }
    if reply.hold {
        let mut sink = [0u8; 1024];
        while matches!(socket.read(&mut sink).await, Ok(n) if n > 0) {}
        let _ = tx.send(());
    }
}

async fn read_request(socket: &mut TcpStream) -> Option<Recorded> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 4096];
    let head_end = loop {
        if let Some(i) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            break i + 4;
        }
        let n = socket.read(&mut chunk).await.ok().filter(|n| *n > 0)?;
        buf.extend_from_slice(&chunk[..n]);
    };
    let head = String::from_utf8_lossy(&buf[..head_end]).into_owned();
    let mut lines = head.split("\r\n");
    let mut start = lines.next()?.split(' ');
    let (method, path) = (start.next()?.to_owned(), start.next()?.to_owned());
    let headers: Vec<(String, String)> = lines
        .filter_map(|l| l.split_once(':'))
        .map(|(n, v)| (n.trim().to_ascii_lowercase(), v.trim().to_owned()))
        .collect();
    let length: usize = headers.iter().find(|(n, _)| n == "content-length").and_then(|(_, v)| v.parse().ok()).unwrap_or(0);
    while buf.len() < head_end + length {
        let n = socket.read(&mut chunk).await.ok().filter(|n| *n > 0)?;
        buf.extend_from_slice(&chunk[..n]);
    }
    let body = String::from_utf8_lossy(&buf[head_end..head_end + length]).into_owned();
    Some(Recorded { method, path, headers, body })
}

/// A clock that moves only when told to.
pub struct FakeClock(Mutex<SystemTime>);

impl FakeClock {
    pub fn at(unix_seconds: u64) -> Arc<Self> {
        Arc::new(Self(Mutex::new(SystemTime::UNIX_EPOCH + Duration::from_secs(unix_seconds))))
    }

    pub fn advance(&self, by: Duration) {
        *self.0.lock().unwrap() += by;
    }
}

impl Clock for FakeClock {
    fn now(&self) -> SystemTime {
        *self.0.lock().unwrap()
    }
}

/// A sleeper that records each duration and returns at once.
#[derive(Default)]
pub struct RecordingSleeper(Mutex<Vec<Duration>>);

impl RecordingSleeper {
    pub fn slept(&self) -> Vec<Duration> {
        self.0.lock().unwrap().clone()
    }
}

impl Sleeper for RecordingSleeper {
    fn sleep(&self, duration: Duration) -> BoxFuture<'static, ()> {
        self.0.lock().unwrap().push(duration);
        Box::pin(async {})
    }
}
