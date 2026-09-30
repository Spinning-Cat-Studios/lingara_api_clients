//! One case's client, built from its `client` block through the public
//! builder only, with a virtual clock, a recording sleeper and, when the
//! case asks, a recording deprecation hook.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use lingara::{BoxFuture, Client, Clock, DeprecationNotice, Sleeper, TokenAuth};
use serde_json::{Value, json};

/// The virtual clock starts here for every case (README, Comparison rules).
const CLOCK_START_S: u64 = 1_790_000_000;

pub struct VirtualClock(AtomicU64);

impl Clock for VirtualClock {
    fn now(&self) -> SystemTime {
        UNIX_EPOCH + Duration::from_secs(self.0.load(Ordering::SeqCst))
    }
}

#[derive(Default)]
pub struct Recorder(Mutex<Vec<Duration>>);

impl Sleeper for Recorder {
    fn sleep(&self, duration: Duration) -> BoxFuture<'static, ()> {
        self.0.lock().unwrap().push(duration);
        Box::pin(async {})
    }
}

pub struct Rig {
    pub client: Client,
    clock: Arc<VirtualClock>,
    sleeps: Arc<Recorder>,
    hook_calls: Arc<Mutex<Vec<Value>>>,
}

impl Rig {
    pub fn advance(&self, seconds: u64) {
        self.clock.0.fetch_add(seconds, Ordering::SeqCst);
    }

    /// Clears what one step records, before the step runs.
    pub fn reset(&self) {
        self.sleeps.0.lock().unwrap().clear();
        self.hook_calls.lock().unwrap().clear();
    }

    pub fn sleeps_s(&self) -> Vec<u64> {
        self.sleeps.0.lock().unwrap().iter().map(|d| d.as_secs_f64().round() as u64).collect()
    }

    pub fn hook_calls(&self) -> Vec<Value> {
        self.hook_calls.lock().unwrap().clone()
    }
}

/// Where the client points: the case server, or a port nothing listens on.
pub struct Urls {
    pub base: String,
    pub token: String,
}

pub fn build(c: &Value, urls: &Urls) -> Result<Rig, String> {
    let clock = Arc::new(VirtualClock(AtomicU64::new(CLOCK_START_S)));
    let sleeps = Arc::new(Recorder::default());
    let hook_calls: Arc<Mutex<Vec<Value>>> = Arc::default();
    let mut b = Client::builder()
        .base_url(&urls.base)
        .token_url(&urls.token)
        .clock(Arc::clone(&clock) as Arc<dyn Clock>)
        .sleeper(Arc::clone(&sleeps) as Arc<dyn Sleeper>);
    if let Some(cred) = c.get("credentials") {
        let text = |k: &str| cred.get(k).and_then(Value::as_str).unwrap_or_default().to_owned();
        let auth = if text("auth") == "post" { TokenAuth::Post } else { TokenAuth::Basic };
        b = b.client_credentials(text("client_id"), text("client_secret")).auth(auth);
    }
    if let Some(scopes) = c.get("scopes").and_then(Value::as_array) {
        b = b.scopes(scopes.iter().filter_map(Value::as_str));
    }
    if let Some(version) = c.get("version").and_then(Value::as_str) {
        b = b.version(version);
    }
    if let Some(n) = c.pointer("/retries/max_attempts").and_then(Value::as_u64) {
        b = b.max_attempts(n as u32);
    }
    if let Some(s) = c.pointer("/retries/retry_after_cap_s").and_then(Value::as_u64) {
        b = b.retry_after_cap(Duration::from_secs(s));
    }
    if let Some(suffix) = c.get("user_agent_suffix").and_then(Value::as_str) {
        b = b.user_agent_suffix(suffix);
    }
    if let Some(ms) = c.get("stream_idle_timeout_ms").and_then(Value::as_u64) {
        b = b.stream_idle_timeout(Duration::from_millis(ms));
    }
    if c.get("deprecation_hook").and_then(Value::as_str) == Some("record") {
        let calls = Arc::clone(&hook_calls);
        b = b.on_deprecation(move |n| calls.lock().unwrap().push(hook_record(n)));
    }
    let client = b.build().map_err(|e| format!("build: {e}"))?;
    Ok(Rig { client, clock, sleeps, hook_calls })
}

fn hook_record(n: &DeprecationNotice) -> Value {
    let seconds = |t: Option<SystemTime>| t.and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_secs());
    let link = n.link.as_ref().map(|l| json!({ "raw": l.raw, "target": l.target.as_ref().map(|t| t.as_str()) }));
    json!({ "version": n.version, "deprecated_at": seconds(n.deprecated_at), "sunset_at": seconds(n.sunset_at), "link": link })
}
