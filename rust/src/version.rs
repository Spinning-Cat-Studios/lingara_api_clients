//! K2: the served version and the deprecation hook (CONTRACT.md K2; ADR
//! 29.9.26p D7). The pin itself is one header the client adds; this reads
//! what came back.

use std::collections::HashSet;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::Url;
use reqwest::header::HeaderMap;

use crate::generated::spec_version::GENERATED_FOR_VERSION;

/// What a response under a deprecated version says about it. An
/// unparseable header leaves its parsed field `None`, never an error.
#[derive(Clone, Debug)]
#[non_exhaustive]
pub struct DeprecationNotice {
    /// The `Lingara-Version` echo, which may be absent.
    pub version: Option<String>,
    /// `Deprecation`, parsed from `@<unix seconds>`.
    pub deprecated_at: Option<SystemTime>,
    /// `Sunset`, parsed from an IMF-fixdate.
    pub sunset_at: Option<SystemTime>,
    pub link: Option<Link>,
    /// The raw `Deprecation` header.
    pub deprecation: String,
    /// The raw `Sunset` header.
    pub sunset: Option<String>,
}

/// A `Link` header: its raw value, and its target resolved against the
/// request URL (RFC 8288 §3.2).
#[derive(Clone, Debug)]
#[non_exhaustive]
pub struct Link {
    pub raw: String,
    pub target: Option<Url>,
}

/// Called once per response that carries `Deprecation`.
pub(crate) type DeprecationHook = Arc<dyn Fn(&DeprecationNotice) + Send + Sync + 'static>;

pub(crate) fn parse_deprecation(value: &str) -> Option<SystemTime> {
    let seconds: i64 = value.trim().strip_prefix('@')?.parse().ok()?;
    let magnitude = Duration::from_secs(seconds.unsigned_abs());
    if seconds >= 0 { UNIX_EPOCH.checked_add(magnitude) } else { UNIX_EPOCH.checked_sub(magnitude) }
}

/// An IMF-fixdate only (`Sun, 06 Nov 1994 08:49:37 GMT`): httpdate also
/// reads the two obsolete formats, which `Sunset` does not allow.
pub(crate) fn parse_sunset(value: &str) -> Option<SystemTime> {
    let value = value.trim();
    let imf = value.len() == 29 && value.as_bytes().get(3) == Some(&b',') && value.ends_with(" GMT");
    if !imf {
        return None;
    }
    httpdate::parse_http_date(value).ok()
}

pub(crate) fn parse_link(raw: &str, request_url: &str) -> Link {
    let target = raw
        .trim_start()
        .strip_prefix('<')
        .and_then(|rest| rest.split_once('>'))
        .and_then(|(target, _)| Url::parse(request_url).ok()?.join(target).ok());
    Link { raw: raw.to_owned(), target }
}

/// The notice for a response, or `None` when it carries no `Deprecation`.
pub(crate) fn deprecation_notice(headers: &HeaderMap, request_url: &str) -> Option<DeprecationNotice> {
    let text = |name: &str| headers.get(name).and_then(|v| v.to_str().ok()).map(str::to_owned);
    let deprecation = text("deprecation")?;
    let sunset = text("sunset");
    Some(DeprecationNotice {
        version: text("lingara-version"),
        deprecated_at: parse_deprecation(&deprecation),
        sunset_at: sunset.as_deref().and_then(parse_sunset),
        link: text("link").map(|raw| parse_link(&raw, request_url)),
        deprecation,
        sunset,
    })
}

/// What `report` did with a notice. Returned so the tests can see it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Reported {
    Hook,
    HookPanicked,
    Warned,
    AlreadyWarned,
}

/// Per client: reads the served version off each response and reports a
/// deprecation once per response to the hook or, with no hook, warns once
/// per version id. Separately, warns once per served id that is not the
/// version the models were generated from (ADR 30.9.26a §4).
pub(crate) struct VersionObserver {
    hook: Option<DeprecationHook>,
    warned: Mutex<HashSet<String>>,
    // Its own set: sharing `warned` would let a version that is both
    // deprecated and mismatched warn only once in total.
    mismatched: Mutex<HashSet<String>>,
}

impl VersionObserver {
    pub fn new(hook: Option<DeprecationHook>) -> Self {
        Self { hook, warned: Mutex::new(HashSet::new()), mismatched: Mutex::new(HashSet::new()) }
    }

    /// The `Lingara-Version` echo, after reporting any deprecation or mismatch.
    pub fn observe(&self, headers: &HeaderMap, request_url: &str) -> Option<String> {
        if let Some(notice) = deprecation_notice(headers, request_url) {
            self.report(&notice);
        }
        let served = headers.get("lingara-version").and_then(|v| v.to_str().ok()).map(str::to_owned);
        if let Some(served) = &served {
            self.check_generated(served);
        }
        served
    }

    /// Whether `served` warned: once per id that is not `GENERATED_FOR_VERSION`.
    pub fn check_generated(&self, served: &str) -> bool {
        if served == GENERATED_FOR_VERSION
            || !self.mismatched.lock().unwrap_or_else(PoisonError::into_inner).insert(served.to_owned())
        {
            return false;
        }
        log::warn!(
            "Lingara API version {served} served this response, but this library's models were generated for \
             {GENERATED_FOR_VERSION}; response shapes may differ. Pin the OAuth client to {GENERATED_FOR_VERSION} \
             or upgrade the library."
        );
        true
    }

    pub fn report(&self, notice: &DeprecationNotice) -> Reported {
        let Some(hook) = &self.hook else {
            return self.warn_once(notice);
        };
        // A hook that panics never fails the call. Under `panic = "abort"`
        // nothing can catch it; the README says so.
        match catch_unwind(AssertUnwindSafe(|| hook(notice))) {
            Ok(()) => Reported::Hook,
            Err(_) => {
                log::debug!("the Lingara deprecation hook panicked; the call continues");
                Reported::HookPanicked
            }
        }
    }

    fn warn_once(&self, notice: &DeprecationNotice) -> Reported {
        // An absent echo counts as one id: the empty string.
        let id = notice.version.clone().unwrap_or_default();
        if !self.warned.lock().unwrap_or_else(PoisonError::into_inner).insert(id.clone()) {
            return Reported::AlreadyWarned;
        }
        let name = if id.is_empty() { "(unnamed)" } else { id.as_str() };
        let sunset = notice.sunset.as_deref().map(|s| format!("; sunset {s}")).unwrap_or_default();
        log::warn!("Lingara API version {name} is deprecated{sunset}. See GET /v1/versions.");
        Reported::Warned
    }
}

#[cfg(test)]
#[path = "tests/version_tests.rs"]
mod tests;
