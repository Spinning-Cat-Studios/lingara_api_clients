//! K1: the token source (CONTRACT.md K1; ADR 29.9.26p D5).
//!
//! `ClientCredentials` caches one token, refreshes it
//! `min(60 s, expires_in / 2)` before it expires, shares one exchange between
//! concurrent callers, and clears only the token a 401 was answered with.
//!
//! The exchange is a spawned task, not any caller's future, so dropping a
//! caller abandons only that caller's wait: the flight runs on, and the task
//! itself writes the cache (or, on failure, nothing) before it completes.

use std::fmt;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, SystemTime};

use base64::Engine as _;
use futures_util::FutureExt as _;
use futures_util::future::Shared;
use reqwest::header::{ACCEPT, AUTHORIZATION, CONTENT_TYPE, HeaderValue, USER_AGENT};
use secrecy::{ExposeSecret as _, SecretString};

use crate::BoxFuture;
use crate::error::{Endpoint, Error, Phase, Refusal, TransportError, TransportKind, from_reqwest, from_response, is_json};
use crate::retry::{RetryPolicy, parse_retry_after, with_retries};

/// Where the client gets its access tokens. A caller may supply their own.
pub trait TokenSource: Send + Sync + 'static {
    /// An access token, cached or freshly exchanged.
    fn token(&self) -> BoxFuture<'_, Result<AccessToken, Error>>;
    /// Forgets `token` only if it is still the cached one.
    fn invalidate(&self, token: &AccessToken);
}

/// An access token. Its `Debug` renders `[REDACTED]`, and it has no
/// `Display`: `expose_secret` is the one way to read it.
#[derive(Clone)]
pub struct AccessToken(SecretString);

impl AccessToken {
    pub fn new(value: impl Into<String>) -> Self {
        Self(SecretString::from(value.into()))
    }

    /// The raw token. The one accessor that does not redact.
    pub fn expose_secret(&self) -> &str {
        self.0.expose_secret()
    }
}

impl fmt::Debug for AccessToken {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_tuple("AccessToken").field(&self.0).finish()
    }
}

/// How the client authenticates to `/oauth/token`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[non_exhaustive]
pub enum TokenAuth {
    /// `client_secret_basic`: the id and secret in an `Authorization` header.
    #[default]
    Basic,
    /// `client_secret_post`: the id and secret in the form body.
    Post,
}

/// Everything the exchange needs besides the credentials.
#[derive(Clone)]
pub(crate) struct ExchangeConfig {
    pub http: reqwest::Client,
    pub token_url: String,
    pub user_agent: String,
    pub auth: TokenAuth,
    pub scopes: Vec<String>,
    pub policy: RetryPolicy,
    pub request_timeout: Duration,
}

type Flight = Shared<BoxFuture<'static, Result<AccessToken, Error>>>;

/// The OAuth 2.0 client-credentials grant against `/oauth/token`. A cheap
/// handle: clones share one cache and one flight.
#[derive(Clone)]
pub struct ClientCredentials {
    inner: Arc<Inner>,
}

struct Inner {
    client_id: String,
    client_secret: SecretString,
    config: ExchangeConfig,
    state: Mutex<State>,
}

#[derive(Default)]
struct State {
    cached: Option<Cached>,
    flight: Option<Flight>,
}

struct Cached {
    token: AccessToken,
    stale_at: SystemTime,
}

impl ClientCredentials {
    pub(crate) fn new(client_id: String, client_secret: SecretString, config: ExchangeConfig) -> Self {
        let state = Mutex::new(State::default());
        Self { inner: Arc::new(Inner { client_id, client_secret, config, state }) }
    }
}

impl TokenSource for ClientCredentials {
    fn token(&self) -> BoxFuture<'_, Result<AccessToken, Error>> {
        let flight = {
            let mut state = self.inner.lock();
            if let Some(cached) = &state.cached {
                if self.inner.config.policy.clock.now() < cached.stale_at {
                    let token = cached.token.clone();
                    return Box::pin(async move { Ok(token) });
                }
            }
            // Stored under the lock the task must take to clear it, so the
            // task can never clear the slot before it is filled.
            state.flight.get_or_insert_with(|| spawn_exchange(Arc::clone(&self.inner))).clone()
        };
        Box::pin(flight)
    }

    fn invalidate(&self, token: &AccessToken) {
        let mut state = self.inner.lock();
        if state.cached.as_ref().is_some_and(|c| c.token.expose_secret() == token.expose_secret()) {
            state.cached = None;
        }
    }
}

impl fmt::Debug for ClientCredentials {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let token = self.inner.lock().cached.as_ref().map(|c| c.token.clone());
        f.debug_struct("ClientCredentials")
            .field("client_id", &self.inner.client_id)
            .field("client_secret", &self.inner.client_secret)
            .field("token", &token)
            .finish()
    }
}

/// Starts the one exchange every waiter shares. A `JoinError` (the task
/// panicked, or the runtime shut down under it) is `connect`: no token was
/// obtained.
fn spawn_exchange(inner: Arc<Inner>) -> Flight {
    let task = tokio::spawn(async move {
        let result = inner.exchange().await;
        let mut state = inner.lock();
        state.flight = None;
        result.map(|cached| {
            let token = cached.token.clone();
            state.cached = Some(cached);
            token
        })
    });
    let flight = task.map(|joined| joined.unwrap_or_else(|e| Err(TransportError::caused_by(TransportKind::Connect, e).into())));
    flight.boxed().shared()
}

impl Inner {
    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    async fn exchange(&self) -> Result<Cached, Error> {
        let policy = &self.config.policy;
        // `obtained_at` is when the request that succeeded was sent.
        let mut sent_at = policy.clock.now();
        let res = with_retries(policy, || {
            sent_at = policy.clock.now();
            self.post()
        })
        .await?;
        if !res.status().is_success() {
            let headers = res.headers();
            let retry_after = parse_retry_after(headers, policy.clock.now());
            let refusal = Refusal { endpoint: Endpoint::Token, status: res.status().as_u16(), json: is_json(headers), retry_after, served_version: None };
            return Err(from_response(res, refusal).await);
        }
        let body = self.bounded(res.bytes()).await?.map_err(|e| from_reqwest(e, Phase::Body))?;
        let (token, expires_in) = grant(&body)?;
        let skew = Duration::from_secs(60).min(expires_in / 2);
        Ok(Cached { token, stale_at: sent_at + expires_in - skew })
    }

    async fn post(&self) -> Result<reqwest::Response, Error> {
        let c = &self.config;
        let mut req = c
            .http
            .post(&c.token_url)
            .header(CONTENT_TYPE, "application/x-www-form-urlencoded")
            .header(ACCEPT, "application/json")
            .header(USER_AGENT, &c.user_agent)
            .body(self.form());
        if c.auth == TokenAuth::Basic {
            req = req.header(AUTHORIZATION, basic(&self.client_id, &self.client_secret));
        }
        self.bounded(req.send()).await?.map_err(|e| from_reqwest(e, Phase::Send))
    }

    /// The form body: the grant, any scopes, and the credentials only under
    /// `client_secret_post`. Never both places.
    fn form(&self) -> String {
        let c = &self.config;
        let mut form = form_urlencoded::Serializer::new(String::new());
        form.append_pair("grant_type", "client_credentials");
        if !c.scopes.is_empty() {
            form.append_pair("scope", &c.scopes.join(" "));
        }
        if c.auth == TokenAuth::Post {
            form.append_pair("client_id", &self.client_id);
            form.append_pair("client_secret", self.client_secret.expose_secret());
        }
        form.finish()
    }

    /// One exchange step under the token request timeout.
    async fn bounded<T>(&self, step: impl Future<Output = T>) -> Result<T, Error> {
        tokio::time::timeout(self.config.request_timeout, step).await.map_err(|_| TransportKind::Timeout.into())
    }
}

/// `Basic base64(form(id) ":" form(secret))`, each half with the WHATWG
/// form serializer (space as `+`), per RFC 6749 §2.3.1. Marked sensitive, so
/// the value never reaches a header's `Debug`.
fn basic(client_id: &str, secret: &SecretString) -> HeaderValue {
    let form = |s: &str| form_urlencoded::byte_serialize(s.as_bytes()).collect::<String>();
    let pair = format!("{}:{}", form(client_id), form(secret.expose_secret()));
    let encoded = base64::engine::general_purpose::STANDARD.encode(pair);
    let mut value = HeaderValue::try_from(format!("Basic {encoded}")).expect("base64 is a valid header value");
    value.set_sensitive(true);
    value
}

/// A `200`'s token and lifetime; `malformed_response` unless it has an
/// `access_token`, an `expires_in` and a `Bearer` `token_type`.
fn grant(body: &[u8]) -> Result<(AccessToken, Duration), Error> {
    let malformed = || Error::from(TransportKind::MalformedResponse);
    let value: serde_json::Value = serde_json::from_slice(body).map_err(|_| malformed())?;
    let token = value.get("access_token").and_then(|v| v.as_str());
    let expires_in = value.get("expires_in").and_then(|v| v.as_f64()).filter(|s| (0.0..=f64::from(u32::MAX)).contains(s));
    let bearer = value.get("token_type").and_then(|v| v.as_str()).is_some_and(|t| t.eq_ignore_ascii_case("bearer"));
    match (token, expires_in, bearer) {
        (Some(token), Some(seconds), true) => Ok((AccessToken::new(token), Duration::from_secs_f64(seconds))),
        _ => Err(malformed()),
    }
}

#[cfg(test)]
#[path = "tests/token_tests.rs"]
mod tests;
