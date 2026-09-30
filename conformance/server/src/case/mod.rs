//! The fixture format (ADR 29.9.26n D10).
//!
//! The schema is closed: every struct denies unknown fields, so a typo in a
//! case fails `check-coverage` rather than a harness. A new key is an ADR's
//! change to this file.
//!
//! The harness-facing half (`client`, `expect`) is parsed only to validate
//! it: the server hands harnesses `Loaded::raw`, never these fields, so
//! rustc sees them as unread.
#![allow(dead_code)]

pub mod check;
mod load;

pub use load::{Loaded, load_dir};
#[cfg(test)]
pub use load::parse;

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// The groups a case may live in; the directory name is the group.
pub const GROUPS: &[&str] = &["op", "k1", "k2", "k3", "k4", "k5", "k6"];

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum Behaviour {
    K1,
    K2,
    K3,
    K4,
    K5,
    K6,
}

pub const BEHAVIOURS: [Behaviour; 6] = [
    Behaviour::K1,
    Behaviour::K2,
    Behaviour::K3,
    Behaviour::K4,
    Behaviour::K5,
    Behaviour::K6,
];

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Case {
    pub id: String,
    pub title: String,
    pub behaviours: Vec<Behaviour>,
    #[serde(default)]
    pub client: Option<Client>,
    pub steps: Vec<Step>,
    #[serde(default)]
    pub exchanges: Option<Exchanges>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Client {
    pub credentials: Option<Credentials>,
    pub scopes: Option<Vec<String>>,
    pub version: Option<String>,
    pub retries: Option<Retries>,
    pub deprecation_hook: Option<HookMode>,
    pub user_agent_suffix: Option<String>,
    pub stream_idle_timeout_ms: Option<u64>,
    pub base_url: Option<BaseUrl>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Credentials {
    pub client_id: String,
    pub client_secret: String,
    pub auth: AuthMethod,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AuthMethod {
    Basic,
    Post,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Retries {
    pub max_attempts: Option<u32>,
    pub retry_after_cap_s: Option<u64>,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum HookMode {
    Record,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BaseUrl {
    Unreachable,
}

/// One step: a call with its expectation, or a clock advance. A struct
/// rather than an untagged enum, so a malformed step gets a readable error.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Step {
    pub call: Option<Call>,
    pub expect: Option<Expect>,
    pub advance_clock_s: Option<u64>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Call {
    pub operation: String,
    pub params: Option<Value>,
    pub body: Option<Value>,
    pub parallel: Option<u32>,
    pub cancel_after_events: Option<u32>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Expect {
    pub outcome: Outcome,
    pub status: Option<u16>,
    pub body: Option<Value>,
    pub events: Option<Vec<Event>>,
    pub error: Option<ExpectedError>,
    pub served_version: Option<String>,
    pub sleeps_s: Option<Vec<u64>>,
    pub hook_calls: Option<Vec<Value>>,
    pub redacted: Option<Vec<String>>,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Outcome {
    Completed,
    Error,
    Cancelled,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Event {
    pub event: String,
    pub data: Value,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ExpectedError {
    pub variant: ErrorVariant,
    pub fields: Option<Value>,
}

/// Spelled as the contract's K3 variants, so a case reads like CONTRACT.md.
#[derive(Debug, Clone, Copy, Deserialize)]
#[allow(clippy::enum_variant_names)]
pub enum ErrorVariant {
    ApiError,
    OAuthError,
    MaintenanceError,
    TransportError,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Exchanges {
    #[serde(default)]
    pub order: Order,
    pub items: Vec<Exchange>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Order {
    #[default]
    Sequence,
    Any,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Exchange {
    pub times: Option<u32>,
    pub group: Option<u32>,
    pub request: Request,
    pub response: Response,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    pub method: String,
    pub path: String,
    pub query: Option<BTreeMap<String, String>>,
    pub headers: Option<BTreeMap<String, HeaderMatch>>,
    pub json: Option<Value>,
    pub form: Option<BTreeMap<String, String>>,
}

/// One header matcher. Written as a one-key map (`{ prefix: … }`); read
/// through `HeaderMatchSpec` because the YAML deserialiser spells an
/// externally tagged enum as a `!tag`, not a map.
#[derive(Debug, Clone, Deserialize)]
#[serde(try_from = "HeaderMatchSpec")]
pub enum HeaderMatch {
    Equals(String),
    Prefix(String),
    Contains(String),
    Pattern(String),
    Absent(bool),
    Basic([String; 2]),
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HeaderMatchSpec {
    equals: Option<String>,
    prefix: Option<String>,
    contains: Option<String>,
    pattern: Option<String>,
    absent: Option<bool>,
    basic: Option<[String; 2]>,
}

impl TryFrom<HeaderMatchSpec> for HeaderMatch {
    type Error = String;

    fn try_from(spec: HeaderMatchSpec) -> Result<Self, String> {
        let all = [
            spec.equals.map(HeaderMatch::Equals),
            spec.prefix.map(HeaderMatch::Prefix),
            spec.contains.map(HeaderMatch::Contains),
            spec.pattern.map(HeaderMatch::Pattern),
            spec.absent.map(HeaderMatch::Absent),
            spec.basic.map(HeaderMatch::Basic),
        ];
        let mut set = all.into_iter().flatten();
        match (set.next(), set.next()) {
            (Some(one), None) => Ok(one),
            _ => Err("a header matcher is exactly one of equals, prefix, contains, pattern, absent, basic".into()),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Response {
    pub delay_ms: Option<u64>,
    pub status: u16,
    /// Values may be written as YAML numbers (`retry-after: 2`); each is
    /// rendered as its string form.
    pub headers: Option<BTreeMap<String, Value>>,
    pub json: Option<Value>,
    pub text: Option<String>,
    pub sse: Option<Sse>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Sse {
    pub chunks: Vec<Chunk>,
    pub then: Then,
    pub disconnect_within_ms: Option<u64>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum Chunk {
    Text(String),
    Hex(HexChunk),
    Pause(PauseChunk),
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HexChunk {
    pub hex: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PauseChunk {
    pub after_ms: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Then {
    Close,
    Reset,
    Hold,
}
