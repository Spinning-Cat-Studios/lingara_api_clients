//! The server's state and the `/__conformance/` surface (ADR 29.9.26n D12).

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use crate::case::{Loaded, Then};
use crate::http::HttpRequest;
use crate::matcher::Exchanges;

/// Slack past a case's longest `disconnect_within_ms` before `finish`
/// stops waiting for a held stream to report.
const SETTLE_SLACK: Duration = Duration::from_millis(500);

/// One line of the arm/finish log `run` cross-checks the results against.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LogEntry {
    Arm(String),
    Finish(String),
}

#[derive(Debug)]
pub struct Armed {
    pub id: String,
    /// Bumped on every arm, so a held stream from an earlier case cannot
    /// report into a later one.
    pub epoch: u64,
    pub exchanges: Exchanges,
    pub mismatches: Vec<String>,
    pub pending_holds: u32,
    /// The case's largest `disconnect_within_ms`.
    pub hold_budget: Duration,
}

#[derive(Debug, Default)]
pub struct State {
    pub cases: BTreeMap<String, Loaded>,
    pub armed: Option<Armed>,
    pub log: Vec<LogEntry>,
    /// Requests that arrived with no case armed.
    pub stray: Vec<String>,
    epochs: u64,
}

#[derive(Debug, Clone, Default)]
pub struct Shared(Arc<Mutex<State>>);

impl Shared {
    pub fn new(cases: Vec<Loaded>) -> Self {
        let cases = cases.into_iter().map(|c| (c.case.id.clone(), c)).collect();
        Self(Arc::new(Mutex::new(State { cases, ..State::default() })))
    }

    pub fn lock(&self) -> MutexGuard<'_, State> {
        self.0.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// A control answer: status and JSON body.
pub type Answer = (u16, Value);

/// Routes one `/__conformance/...` request.
pub async fn answer(shared: &Shared, request: &HttpRequest) -> Answer {
    let rest = request.path.trim_start_matches("/__conformance");
    let parts: Vec<&str> = rest.split('/').filter(|p| !p.is_empty()).collect();
    match (request.method.as_str(), parts.as_slice()) {
        ("GET", ["cases"]) => (200, json!(shared.lock().cases.keys().collect::<Vec<_>>())),
        ("GET", ["cases", id]) => case_json(shared, id),
        ("POST", ["cases", id, "arm"]) => arm(shared, id),
        ("POST", ["cases", id, "finish"]) => finish(shared, id).await,
        _ => (404, json!({ "error": format!("no control route {} {}", request.method, request.path) })),
    }
}

fn case_json(shared: &Shared, id: &str) -> Answer {
    match shared.lock().cases.get(id) {
        Some(loaded) => (200, loaded.raw.clone()),
        None => (404, json!({ "error": format!("no case `{id}`") })),
    }
}

pub fn arm(shared: &Shared, id: &str) -> Answer {
    let mut state = shared.lock();
    let Some(loaded) = state.cases.get(id) else {
        return (404, json!({ "error": format!("no case `{id}`") }));
    };
    if let Some(armed) = &state.armed {
        let error = format!("`{}` is armed; finish it before arming `{id}`", armed.id);
        return (409, json!({ "error": error }));
    }
    let exchanges = Exchanges::new(&loaded.case);
    let hold_budget = Duration::from_millis(hold_budget_ms(loaded));
    state.epochs += 1;
    let epoch = state.epochs;
    let armed = Armed { id: id.to_string(), epoch, exchanges, mismatches: Vec::new(), pending_holds: 0, hold_budget };
    state.armed = Some(armed);
    state.log.push(LogEntry::Arm(id.to_string()));
    (200, json!({ "armed": id }))
}

fn hold_budget_ms(loaded: &Loaded) -> u64 {
    let items = loaded.case.exchanges.iter().flat_map(|x| &x.items);
    let holds = items.filter_map(|i| i.response.sse.as_ref()).filter(|s| s.then == Then::Hold);
    holds.filter_map(|s| s.disconnect_within_ms).max().unwrap_or(0)
}

/// Waits for held streams to settle, then returns the verdict and disarms.
pub async fn finish(shared: &Shared, id: &str) -> Answer {
    let budget = match &shared.lock().armed {
        Some(armed) if armed.id == id => armed.hold_budget,
        _ => return (409, json!({ "error": format!("`{id}` is not armed") })),
    };
    let deadline = Instant::now() + budget + SETTLE_SLACK;
    while shared.lock().armed.as_ref().is_some_and(|a| a.pending_holds > 0) && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    let mut state = shared.lock();
    let Some(armed) = state.armed.take() else {
        return (409, json!({ "error": format!("`{id}` is not armed") }));
    };
    state.log.push(LogEntry::Finish(id.to_string()));
    let mut mismatches = armed.mismatches;
    mismatches.extend(armed.exchanges.unconsumed());
    if armed.pending_holds > 0 {
        mismatches.push(format!("{} held stream(s) still open at finish", armed.pending_holds));
    }
    (200, json!({ "case": id, "pass": mismatches.is_empty(), "mismatches": mismatches }))
}
