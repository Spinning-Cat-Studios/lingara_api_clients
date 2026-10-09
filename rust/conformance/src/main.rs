//! The conformance harness (conformance/README.md, Writing a harness; ADR
//! 29.9.26p D8). Runs every case through the `lingara` crate's public API:
//! each client is built from the case's `client` block through the builder
//! only (`rig.rs`), a `parallel: n` step is `n` futures under `join_all`, and
//! `cancel_after_events: n` drops the stream after its n-th event
//! (`observe.rs`). The `events` and `tail` steps drive the event helpers
//! (`events.rs`, ADR 30.9.26aa D9).

mod compare;
mod events;
mod observe;
mod rig;

use std::io::Write as _;
use std::process::ExitCode;
use std::time::Instant;

use futures_util::future::join_all;
use serde::de::DeserializeOwned;
use serde_json::{Value, json};

use compare::{Observed, compare, substitute};
use observe::{empty_result, json_result, minted_result, stream};
use rig::{Rig, Urls};

/// The crate under test's own manifest, for its version.
const LIBRARY_MANIFEST: &str = include_str!("../../Cargo.toml");

struct Env {
    base: String,
    token: String,
    control: String,
    out: String,
    only: Option<Vec<String>>,
}

fn env() -> Result<Env, String> {
    let var = |name: &str| std::env::var(name).map_err(|_| format!("{name} is not set: run this through conformance-server run"));
    let only = std::env::var("LINGARA_CONFORMANCE_ONLY").ok().map(|s| s.split(',').map(|id| id.trim().to_owned()).filter(|id| !id.is_empty()).collect());
    Ok(Env {
        base: var("LINGARA_CONFORMANCE_BASE_URL")?,
        token: var("LINGARA_CONFORMANCE_TOKEN_URL")?,
        control: var("LINGARA_CONFORMANCE_CONTROL_URL")?,
        out: var("LINGARA_CONFORMANCE_OUT")?,
        only,
    })
}

fn library_version() -> &'static str {
    LIBRARY_MANIFEST.lines().find_map(|l| l.strip_prefix("version = \"")?.strip_suffix('"')).unwrap_or("unknown")
}

async fn control(http: &reqwest::Client, method: reqwest::Method, url: String) -> Result<Value, String> {
    let res = http.request(method, &url).send().await.map_err(|e| format!("{url}: {e}"))?;
    let status = res.status();
    let text = res.text().await.map_err(|e| format!("{url}: {e}"))?;
    if !status.is_success() {
        return Err(format!("{url}: {status} {text}"));
    }
    serde_json::from_str(&text).map_err(|e| format!("{url}: {e}"))
}

#[tokio::main]
async fn main() -> ExitCode {
    match run().await {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::FAILURE,
        Err(message) => {
            eprintln!("✗ harness: {message}");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> Result<bool, String> {
    let env = env()?;
    let http = reqwest::Client::new();
    let ids = control(&http, reqwest::Method::GET, format!("{}/cases", env.control)).await?;
    let ids: Vec<String> = serde_json::from_value(ids).map_err(|e| e.to_string())?;
    let mut all_passed = true;
    for id in ids.iter().filter(|id| env.only.as_ref().is_none_or(|only| only.contains(id))) {
        all_passed &= run_case(&env, &http, id).await?;
    }
    Ok(all_passed)
}

async fn run_case(env: &Env, http: &reqwest::Client, id: &str) -> Result<bool, String> {
    let started = Instant::now();
    let case = control(http, reqwest::Method::GET, format!("{}/cases/{id}", env.control)).await?;
    control(http, reqwest::Method::POST, format!("{}/cases/{id}/arm", env.control)).await?;
    let client_mismatches = match steps(env, &case).await {
        Ok(mismatches) => mismatches,
        Err(e) => vec![format!("harness: {e}")],
    };
    let verdict = control(http, reqwest::Method::POST, format!("{}/cases/{id}/finish", env.control)).await?;
    let server_mismatches = verdict.get("mismatches").cloned().unwrap_or(json!([]));
    let pass = client_mismatches.is_empty() && server_mismatches.as_array().is_some_and(Vec::is_empty);
    let line = json!({
        "case": id, "lang": "rust", "library_version": library_version(),
        "result": if pass { "pass" } else { "fail" },
        "client_mismatches": client_mismatches, "server_mismatches": server_mismatches,
        "duration_ms": started.elapsed().as_millis() as u64,
    });
    let mut out = std::fs::OpenOptions::new().create(true).append(true).open(&env.out).map_err(|e| format!("{}: {e}", env.out))?;
    writeln!(out, "{line}").map_err(|e| e.to_string())?;
    if !pass {
        eprintln!("✗ {id}: {}", json!({ "client": line["client_mismatches"], "server": line["server_mismatches"] }));
    }
    Ok(pass)
}

async fn steps(env: &Env, case: &Value) -> Result<Vec<String>, String> {
    let urls = urls(env, case).await?;
    let rig = rig::build(case.get("client").unwrap_or(&json!({})), &urls)?;
    let mut mismatches = Vec::new();
    for step in case.get("steps").and_then(Value::as_array).cloned().unwrap_or_default() {
        if let Some(seconds) = step.get("advance_clock_s").and_then(Value::as_u64) {
            rig.advance(seconds);
        }
        let Some(expect) = step.get("expect").map(|e| substitute(e, &env.base)) else { continue };
        if let Some(call) = step.get("call") {
            mismatches.extend(run_step(&rig, call, &expect).await?);
        } else if let Some(block) = step.get("events") {
            mismatches.extend(run_helper(&rig, "events", events::events_step(&rig.client, block), &expect).await?);
        } else if let Some(block) = step.get("tail") {
            mismatches.extend(run_helper(&rig, "tail", events::tail_step(&rig.client, block), &expect).await?);
        }
    }
    Ok(mismatches)
}

/// `base_url: unreachable` points the client at a port bound and released.
async fn urls(env: &Env, case: &Value) -> Result<Urls, String> {
    if case.pointer("/client/base_url").and_then(Value::as_str) != Some("unreachable") {
        return Ok(Urls { base: env.base.clone(), token: env.token.clone() });
    }
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.map_err(|e| e.to_string())?;
    let base = format!("http://{}", listener.local_addr().map_err(|e| e.to_string())?);
    Ok(Urls { token: format!("{base}/oauth/token"), base })
}

async fn run_step(rig: &Rig, call: &Value, expect: &Value) -> Result<Vec<String>, String> {
    rig.reset();
    let n = call.get("parallel").and_then(Value::as_u64).unwrap_or(1) as usize;
    let runs = join_all((0..n).map(|_| invoke(rig, call))).await;
    let operation = call.get("operation").and_then(Value::as_str).unwrap_or_default();
    let mut mismatches = Vec::new();
    for (i, run) in runs.into_iter().enumerate() {
        let mut seen = run?;
        seen.sleeps_s = rig.sleeps_s();
        seen.hook_calls = rig.hook_calls();
        seen.renderings.extend([format!("{:?}", rig.client), format!("{:#?}", rig.client)]);
        let label = if n > 1 { format!("call {}: ", i + 1) } else { String::new() };
        mismatches.extend(compare(expect, &seen).into_iter().map(|m| format!("{operation}: {label}{m}")));
    }
    Ok(mismatches)
}

/// An `events` or `tail` step: one run of the helper, compared like a call.
async fn run_helper(rig: &Rig, label: &str, run: impl Future<Output = Result<Observed, String>>, expect: &Value) -> Result<Vec<String>, String> {
    rig.reset();
    let mut seen = run.await?;
    seen.sleeps_s = rig.sleeps_s();
    seen.renderings.extend([format!("{:?}", rig.client), format!("{:#?}", rig.client)]);
    Ok(compare(expect, &seen).into_iter().map(|m| format!("{label}: {m}")).collect())
}

fn input<T: DeserializeOwned>(call: &Value, key: &str) -> Result<T, String> {
    serde_json::from_value(call.get(key).cloned().unwrap_or(Value::Null)).map_err(|e| format!("{key}: {e}"))
}

fn id(call: &Value) -> Result<String, String> {
    param(call, "id")
}

fn param(call: &Value, name: &str) -> Result<String, String> {
    call.get("params").and_then(|p| p.get(name)).and_then(Value::as_str).map(str::to_owned).ok_or_else(|| format!("params.{name} is missing"))
}

async fn invoke(rig: &Rig, call: &Value) -> Result<Observed, String> {
    let c = &rig.client;
    let cancel = call.get("cancel_after_events").and_then(Value::as_u64).map(|n| n as usize);
    Ok(match call.get("operation").and_then(Value::as_str).unwrap_or_default() {
        "generateVocabulary" => stream(c.generate_vocabulary(&input(call, "body")?).await, cancel).await,
        "createLessonPlan" => stream(c.create_lesson_plan(&input(call, "body")?).await, cancel).await,
        "streamLessonPlan" => stream(c.stream_lesson_plan(&id(call)?).await, cancel).await,
        "sendTutorMessage" => stream(c.send_tutor_message(&input(call, "body")?).await, cancel).await,
        "getLessonPlan" => json_result(c.get_lesson_plan(&id(call)?).await),
        "getUsage" => json_result(c.get_usage().await),
        "getOpenApiDocument" => json_result(c.get_open_api_document().await),
        "listApiVersions" => json_result(c.list_api_versions().await),
        "getApiVersion" => json_result(c.get_api_version(&id(call)?).await),
        "getAsyncApiDocument" => json_result(c.get_async_api_document().await),
        "listEvents" => json_result(c.list_events(&events::list_params(call)?).await),
        "streamEvents" => stream(c.stream_events(&events::stream_params(call)?).await, cancel).await,
        "sendEvent" => {
            let (event, options) = events::send_input(call)?;
            // The operation's one success status: `ApiResponse` is any 2xx.
            let mut seen = json_result(c.send_event(&event, options).await);
            seen.status = seen.status.map(|_| 202);
            seen
        }
        "createEmbedToken" => minted_result(c.create_embed_token(&input(call, "body")?).await),
        "deleteEmbedPlayer" => empty_result(c.delete_embed_player(&param(call, "player_ref")?).await),
        "sendDialogueTurn" => stream(c.send_dialogue_turn(&input(call, "body")?).await, cancel).await,
        other => return Err(format!("no operation {other}")),
    })
}

