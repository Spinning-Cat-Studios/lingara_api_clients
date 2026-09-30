//! `serve`: one port, two surfaces (ADR 29.9.26n D12).

use serde_json::json;
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

use crate::case::{Response, Then};
use crate::control::{self, Shared};
use crate::http::{self, HttpRequest};
use crate::matcher;
use crate::replay::{self, Replayed};

const MISMATCH_STATUS: u16 = 599;

/// Binds `127.0.0.1:<port>` (`0` picks one) and serves until dropped.
pub async fn start(shared: Shared, port: u16) -> std::io::Result<(u16, JoinHandle<()>)> {
    let listener = TcpListener::bind(("127.0.0.1", port)).await?;
    let port = listener.local_addr()?.port();
    let handle = tokio::spawn(async move {
        while let Ok((stream, _)) = listener.accept().await {
            let shared = shared.clone();
            tokio::spawn(async move { connection(shared, stream).await });
        }
    });
    Ok((port, handle))
}

async fn connection(shared: Shared, mut stream: TcpStream) {
    let request = match http::read_request(&mut stream).await {
        Ok(Some(request)) => request,
        _ => return,
    };
    if request.path.starts_with("/__conformance/") || request.path == "/__conformance" {
        let (status, body) = control::answer(&shared, &request).await;
        let bytes = serde_json::to_vec(&body).unwrap_or_default();
        let _ = stream.write_all(&http::whole(status, "application/json", &bytes)).await;
        let _ = stream.shutdown().await;
        return;
    }
    replay_one(&shared, stream, &request).await;
}

/// What the armed case says to do with a replay-surface request.
enum Plan {
    Replay { epoch: u64, response: Response },
    Refuse(serde_json::Value),
}

fn plan(shared: &Shared, request: &HttpRequest) -> Plan {
    let mut state = shared.lock();
    let Some(armed) = state.armed.as_mut() else {
        let reason = format!("{} {}: no case is armed", request.method, request.path);
        state.stray.push(reason.clone());
        return Plan::Refuse(json!({ "conformance_mismatch": reason, "expected": [] }));
    };
    if let Err(e) = matcher::check_user_agent(request.header("user-agent")) {
        armed.mismatches.push(format!("{} {}: {e}", request.method, request.path));
    }
    match armed.exchanges.take(request) {
        Ok(response) => {
            if response.sse.as_ref().is_some_and(|s| s.then == Then::Hold) {
                armed.pending_holds += 1;
            }
            Plan::Replay { epoch: armed.epoch, response }
        }
        Err(mismatch) => {
            armed.mismatches.push(mismatch.reason.clone());
            Plan::Refuse(mismatch.body())
        }
    }
}

async fn replay_one(shared: &Shared, mut stream: TcpStream, request: &HttpRequest) {
    let (epoch, response) = match plan(shared, request) {
        Plan::Replay { epoch, response } => (epoch, response),
        Plan::Refuse(body) => {
            let bytes = serde_json::to_vec(&body).unwrap_or_default();
            let answer = http::whole(MISMATCH_STATUS, "application/json", &bytes);
            let _ = stream.write_all(&answer).await;
            let _ = stream.shutdown().await;
            return;
        }
    };
    let outcome = replay::respond(stream, &response).await;
    let held = response.sse.as_ref().is_some_and(|s| s.then == Then::Hold);
    if !held {
        return;
    }
    let mut state = shared.lock();
    let Some(armed) = state.armed.as_mut().filter(|a| a.epoch == epoch) else { return };
    armed.pending_holds = armed.pending_holds.saturating_sub(1);
    // A write that failed mid-script means the client had already gone.
    if matches!(outcome, Ok(Replayed::Held { in_time: false })) {
        let ms = response.sse.as_ref().and_then(|s| s.disconnect_within_ms).unwrap_or(0);
        let what = format!("{} {}: hold — the client did not disconnect within {ms} ms", request.method, request.path);
        armed.mismatches.push(what);
    }
}
