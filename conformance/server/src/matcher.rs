//! Request matching (ADR 29.9.26n D10, D12).
//!
//! An armed case's exchanges form *units*: under `order: sequence` each item
//! is its own unit unless consecutive items share a `group`, which makes them
//! one; under `order: any` every item is one unit. Units are consumed in
//! order, and inside the current unit any item with `times` left may match.

use std::collections::BTreeMap;
use std::sync::LazyLock;

use base64::Engine as _;
use regex::Regex;
use serde_json::{Value, json};

use crate::case::{Case, Exchange, HeaderMatch, Order, Request, Response};
use crate::http::HttpRequest;

/// D8's `User-Agent` pattern, checked on every replayed request.
static USER_AGENT: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(concat!(
        r"^lingara-(typescript|rust|go|java|kotlin|ruby|php)/",
        r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)? ",
        r"\([\x20-\x28\x2A-\x7E]+\)( .+)?$",
    ))
    .expect("the D8 pattern compiles")
});

/// C6a's limit on the whole `<version>`.
const MAX_VERSION_BYTES: usize = 64;

/// Why a request was not replayed, and what the case expected instead.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Mismatch {
    pub reason: String,
    pub expected: Vec<String>,
}

impl Mismatch {
    /// The `599` body: the server's readable reason for the harness's log.
    pub fn body(&self) -> Value {
        json!({ "conformance_mismatch": self.reason, "expected": self.expected })
    }
}

/// The exchange state of one armed case.
#[derive(Debug)]
pub struct Exchanges {
    items: Vec<Exchange>,
    remaining: Vec<u32>,
    units: Vec<Vec<usize>>,
}

impl Exchanges {
    pub fn new(case: &Case) -> Self {
        let (order, items) = match &case.exchanges {
            Some(x) => (x.order, x.items.clone()),
            None => (Order::Sequence, Vec::new()),
        };
        let remaining = items.iter().map(|i| i.times.unwrap_or(1)).collect();
        let units = units_of(order, &items);
        Self { items, remaining, units }
    }

    /// Consumes the item `request` matches and returns its response, or
    /// says why nothing in the current unit matched. Nothing is consumed on
    /// a mismatch.
    pub fn take(&mut self, request: &HttpRequest) -> Result<Response, Mismatch> {
        let Some(unit) = self.units.iter().find(|u| u.iter().any(|&i| self.remaining[i] > 0)) else {
            let reason = format!("{} {}: every expected exchange was already consumed", request.method, request.path);
            return Err(Mismatch { reason, expected: Vec::new() });
        };
        let open: Vec<usize> = unit.iter().copied().filter(|&i| self.remaining[i] > 0).collect();
        let expected: Vec<String> = open.iter().map(|&i| describe(&self.items[i].request)).collect();
        let same_route: Vec<usize> =
            open.into_iter().filter(|&i| routes_match(&self.items[i].request, request)).collect();
        let mut first_failures = None;
        for i in same_route {
            let failures = check(&self.items[i].request, request);
            if failures.is_empty() {
                self.remaining[i] -= 1;
                return Ok(self.items[i].response.clone());
            }
            first_failures.get_or_insert(failures);
        }
        let reason = match first_failures {
            Some(f) => format!("{} {}: {}", request.method, request.path, f.join("; ")),
            None => format!("{} {}: not the next expected request", request.method, request.path),
        };
        Err(Mismatch { reason, expected })
    }

    /// One line per item not consumed its `times`.
    pub fn unconsumed(&self) -> Vec<String> {
        let items = self.items.iter().zip(&self.remaining).filter(|(_, left)| **left > 0);
        items
            .map(|(item, left)| format!("{}: {left} more expected", describe(&item.request)))
            .collect()
    }
}

fn units_of(order: Order, items: &[Exchange]) -> Vec<Vec<usize>> {
    if order == Order::Any {
        return vec![(0..items.len()).collect()];
    }
    let mut units: Vec<(Option<u32>, Vec<usize>)> = Vec::new();
    for (i, item) in items.iter().enumerate() {
        match units.last_mut() {
            Some((Some(g), members)) if item.group == Some(*g) => members.push(i),
            _ => units.push((item.group, vec![i])),
        }
    }
    units.into_iter().map(|(_, members)| members).collect()
}

fn describe(request: &Request) -> String {
    format!("{} {}", request.method, request.path)
}

fn routes_match(expected: &Request, actual: &HttpRequest) -> bool {
    expected.method == actual.method && expected.path == actual.path
}

/// Every way `actual` differs from `expected` beyond its route.
pub fn check(expected: &Request, actual: &HttpRequest) -> Vec<String> {
    let mut failures = Vec::new();
    for (name, matcher) in expected.headers.iter().flatten() {
        if let Err(e) = header_matches(matcher, actual.header(name)) {
            failures.push(format!("header `{name}` {e}"));
        }
    }
    let query = form_pairs(actual.query.as_deref().unwrap_or("").as_bytes());
    let expected_query = expected.query.clone().unwrap_or_default();
    if query.as_ref() != Ok(&expected_query) {
        failures.push(format!("query {:?} is not {expected_query:?}", actual.query));
    }
    if let Some(json) = &expected.json {
        match serde_json::from_slice::<Value>(&actual.body) {
            Ok(body) if body == *json => {}
            _ => failures.push(format!("JSON body is not {json}")),
        }
    }
    if let Some(form) = &expected.form
        && form_pairs(&actual.body).as_ref() != Ok(form)
    {
        failures.push(format!("form body {:?} is not {form:?}", String::from_utf8_lossy(&actual.body)));
    }
    failures
}

fn header_matches(matcher: &HeaderMatch, value: Option<&str>) -> Result<(), String> {
    let ok = match (matcher, value) {
        (HeaderMatch::Absent(true), v) => v.is_none(),
        (HeaderMatch::Absent(false), v) => v.is_some(),
        (_, None) => return Err("is missing".into()),
        (HeaderMatch::Equals(want), Some(v)) => v == want,
        (HeaderMatch::Prefix(want), Some(v)) => v.starts_with(want.as_str()),
        (HeaderMatch::Contains(want), Some(v)) => v.contains(want.as_str()),
        (HeaderMatch::Pattern(want), Some(v)) => Regex::new(want).is_ok_and(|re| re.is_match(v)),
        (HeaderMatch::Basic([id, secret]), Some(v)) => basic_halves(v) == Some((id.clone(), secret.clone())),
    };
    if ok { Ok(()) } else { Err(format!("{value:?} does not satisfy {matcher:?}")) }
}

/// `Basic base64(form_urlencode(id) ":" form_urlencode(secret))` → the two
/// decoded halves (RFC 6749 §2.3.1: `+` is a space, then percent-decoding).
pub fn basic_halves(header: &str) -> Option<(String, String)> {
    let encoded = header.strip_prefix("Basic ")?.trim();
    let decoded = base64::engine::general_purpose::STANDARD.decode(encoded).ok()?;
    let text = String::from_utf8(decoded).ok()?;
    let (id, secret) = text.split_once(':')?;
    Some((form_decode(id)?, form_decode(secret)?))
}

fn form_decode(s: &str) -> Option<String> {
    let spaced = s.replace('+', " ");
    percent_encoding::percent_decode_str(&spaced).decode_utf8().ok().map(|c| c.into_owned())
}

/// An `application/x-www-form-urlencoded` body as a map; a repeated key is
/// refused, since D2's form never repeats one.
fn form_pairs(body: &[u8]) -> Result<BTreeMap<String, String>, ()> {
    let text = std::str::from_utf8(body).map_err(|_| ())?;
    let mut out = BTreeMap::new();
    for pair in text.split('&').filter(|p| !p.is_empty()) {
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        let (k, v) = (form_decode(k).ok_or(())?, form_decode(v).ok_or(())?);
        if out.insert(k, v).is_some() {
            return Err(());
        }
    }
    Ok(out)
}

/// D8: the library's product token first, SemVer core, a visible-ASCII
/// runtime comment, and at most 64 bytes of version.
pub fn check_user_agent(value: Option<&str>) -> Result<(), String> {
    let Some(ua) = value else { return Err("no User-Agent".into()) };
    if !USER_AGENT.is_match(ua) {
        return Err(format!("User-Agent `{ua}` does not match D8's pattern"));
    }
    let version = ua.split_once('/').and_then(|(_, rest)| rest.split(' ').next()).unwrap_or("");
    if version.len() > MAX_VERSION_BYTES {
        return Err(format!("User-Agent version `{version}` is over {MAX_VERSION_BYTES} bytes"));
    }
    Ok(())
}
