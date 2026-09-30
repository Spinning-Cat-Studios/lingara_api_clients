//! `ClientBuilder`: every option C2 names, in snake case (ADR 29.9.26p D4).
//! The conformance harness builds each client from these and nothing else.
//! Who the client is and where it points are set here; the timing, retry and
//! seam options are in `builder_options.rs`.

use std::sync::Arc;
use std::time::Duration;

use reqwest::Url;
use secrecy::SecretString;

use crate::client::{Client, Inner};
use crate::pipeline::user_agent;
use crate::retry::RetryPolicy;
use crate::seams::{SystemClock, TokioSleeper};
use crate::token::{ClientCredentials, ExchangeConfig, TokenAuth, TokenSource};
use crate::version::{DeprecationHook, DeprecationNotice, VersionObserver};

pub const DEFAULT_BASE_URL: &str = "https://api.getlingara.com";
pub const DEFAULT_TOKEN_URL: &str = "https://api.getlingara.com/oauth/token";

/// Why `build()` refused. Not a K3 variant: C2's four describe a call's
/// outcome, and a misconfiguration is not one.
#[derive(Debug, thiserror::Error)]
#[non_exhaustive]
pub enum BuildError {
    #[error("version must not be empty")]
    EmptyVersion,
    #[error("{option} is not a valid URL: {reason}")]
    InvalidUrl { option: &'static str, reason: String },
    #[error("could not build the default HTTP client")]
    Http(#[source] reqwest::Error),
}

/// Configures a [`Client`]. `Client::builder()` starts one.
#[must_use]
pub struct ClientBuilder {
    credentials: Option<(String, SecretString)>,
    auth: TokenAuth,
    scopes: Vec<String>,
    token_source: Option<Arc<dyn TokenSource>>,
    base_url: String,
    token_url: String,
    version: Option<String>,
    on_deprecation: Option<DeprecationHook>,
    pub(crate) policy: RetryPolicy,
    pub(crate) stream_idle_timeout: Duration,
    pub(crate) token_request_timeout: Duration,
    pub(crate) user_agent_suffix: Option<String>,
    pub(crate) http_client: Option<reqwest::Client>,
}

impl Default for ClientBuilder {
    fn default() -> Self {
        Self {
            credentials: None,
            auth: TokenAuth::Basic,
            scopes: Vec::new(),
            token_source: None,
            base_url: DEFAULT_BASE_URL.to_owned(),
            token_url: DEFAULT_TOKEN_URL.to_owned(),
            version: None,
            on_deprecation: None,
            policy: RetryPolicy {
                max_attempts: 3,
                retry_after_cap: Duration::from_secs(60),
                clock: Arc::new(SystemClock),
                sleeper: Arc::new(TokioSleeper),
            },
            stream_idle_timeout: Duration::from_secs(120),
            token_request_timeout: Duration::from_secs(30),
            user_agent_suffix: None,
            http_client: None,
        }
    }
}

impl ClientBuilder {
    /// The OAuth client id and secret (K1). Without them, only the three
    /// operations that need no token can succeed.
    pub fn client_credentials(mut self, client_id: impl Into<String>, client_secret: impl Into<String>) -> Self {
        self.credentials = Some((client_id.into(), SecretString::from(client_secret.into())));
        self
    }

    /// `TokenAuth::Basic` (the default) or `TokenAuth::Post`.
    pub fn auth(mut self, auth: TokenAuth) -> Self {
        self.auth = auth;
        self
    }

    /// The scopes to request. None (the default) sends no `scope` at all.
    pub fn scopes<S: Into<String>>(mut self, scopes: impl IntoIterator<Item = S>) -> Self {
        self.scopes = scopes.into_iter().map(Into::into).collect();
        self
    }

    /// Replaces the client-credentials source with a caller's own.
    pub fn token_source(mut self, source: Arc<dyn TokenSource>) -> Self {
        self.token_source = Some(source);
        self
    }

    pub fn base_url(mut self, url: impl Into<String>) -> Self {
        self.base_url = url.into();
        self
    }

    pub fn token_url(mut self, url: impl Into<String>) -> Self {
        self.token_url = url.into();
        self
    }

    /// Pins every `/v1` request to this API version (K2). Only an empty id
    /// is refused; the server answers an unknown one.
    pub fn version(mut self, version: impl Into<String>) -> Self {
        self.version = Some(version.into());
        self
    }

    /// Called once per response under a deprecated version. Without one,
    /// the client logs one `log::warn!` per version id.
    pub fn on_deprecation(mut self, hook: impl Fn(&DeprecationNotice) + Send + Sync + 'static) -> Self {
        self.on_deprecation = Some(Arc::new(hook));
        self
    }

    pub fn build(self) -> Result<Client, BuildError> {
        if self.version.as_deref() == Some("") {
            return Err(BuildError::EmptyVersion);
        }
        check_url("base_url", &self.base_url)?;
        check_url("token_url", &self.token_url)?;
        let http = match self.http_client {
            Some(client) => client,
            None => default_http_client()?,
        };
        let user_agent = user_agent(self.user_agent_suffix.as_deref());
        let credentials = match (&self.token_source, self.credentials) {
            (None, Some((id, secret))) => {
                let config = ExchangeConfig {
                    http: http.clone(),
                    token_url: self.token_url,
                    user_agent: user_agent.clone(),
                    auth: self.auth,
                    scopes: self.scopes,
                    policy: self.policy.clone(),
                    request_timeout: self.token_request_timeout,
                };
                Some(ClientCredentials::new(id, secret, config))
            }
            _ => None,
        };
        let tokens = self.token_source.or_else(|| credentials.clone().map(|c| Arc::new(c) as Arc<dyn TokenSource>));
        Ok(Client::from_inner(Inner {
            http,
            base_url: self.base_url.trim_end_matches('/').to_owned(),
            version: self.version,
            versions: VersionObserver::new(self.on_deprecation),
            policy: self.policy,
            idle: self.stream_idle_timeout,
            user_agent,
            tokens,
            credentials,
        }))
    }
}

fn check_url(option: &'static str, url: &str) -> Result<(), BuildError> {
    Url::parse(url).map(drop).map_err(|e| BuildError::InvalidUrl { option, reason: e.to_string() })
}

/// A 30 s connect timeout and no total or read timeout: a stream may run as
/// long as its events keep coming.
fn default_http_client() -> Result<reqwest::Client, BuildError> {
    reqwest::Client::builder().connect_timeout(Duration::from_secs(30)).build().map_err(BuildError::Http)
}
