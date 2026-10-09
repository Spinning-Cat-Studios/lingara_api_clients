//! The request pipeline behind every operation (CONTRACT.md K1–K6; ADR
//! 29.9.26p D4): the headers every call carries, the one 401 retry, the
//! `Retry-After` loop, the error mapping, and turning a `200` into a stream,
//! a JSON result or an empty one.

use futures_util::StreamExt as _;
use reqwest::Method;
use reqwest::header::{ACCEPT, AUTHORIZATION, HeaderValue, USER_AGENT};
use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::client::{ApiResponse, Client};
use crate::error::{Endpoint, Error, Phase, Refusal, TransportKind, from_reqwest, from_response, is_json, media_type};
use crate::retry::{parse_retry_after, with_retries, with_token_retry};
use crate::stream::{EventStream, StreamRoute};
use crate::token::AccessToken;

include!(concat!(env!("OUT_DIR"), "/build_info.rs"));

const SSE: &str = "text/event-stream";
const JSON: &str = "application/json";

/// `lingara-rust/<version> (rust/<rustc>; <target>)`, then a caller's own
/// product token after one space (K6).
pub(crate) fn user_agent(suffix: Option<&str>) -> String {
    let own = format!("lingara-rust/{} (rust/{RUSTC_VERSION}; {TARGET})", env!("CARGO_PKG_VERSION"));
    match suffix {
        Some(suffix) if !suffix.is_empty() => format!("{own} {suffix}"),
        _ => own,
    }
}

/// One `/v1` request, before auth.
pub(crate) struct Req<'a, B: ?Sized> {
    pub method: Method,
    pub url: &'a str,
    pub body: Option<&'a B>,
    pub accept: &'static str,
    pub needs_token: bool,
    /// Sent unchanged on every attempt: an `Idempotency-Key` (K4) or a
    /// `Last-Event-ID` (K5a).
    pub headers: &'a [(&'static str, String)],
    /// K4's `Retry-After` loop. A tail open bypasses it: the tail's own
    /// failure count is its only retry budget (CONTRACT.md K5a).
    pub retries: bool,
}

impl Client {
    /// Resolves once the response headers are in: a refusal comes from this
    /// `await`, an in-stream failure from the stream's items.
    pub(crate) async fn stream<E, B>(&self, route: &StreamRoute, id: Option<&str>, body: Option<&B>) -> Result<EventStream<E>, Error>
    where
        E: DeserializeOwned,
        B: Serialize + ?Sized,
    {
        debug_assert_eq!(route.request_body.is_some(), body.is_some(), "{}: body", route.operation_id);
        let mut path = route.path.to_owned();
        for param in route.path_params {
            path = path.replace(&format!("{{{param}}}"), &encode_segment(id.unwrap_or_default()));
        }
        let url = self.url(&path);
        let method = if route.method == "GET" { Method::GET } else { Method::POST };
        let req = Req { method, url: &url, body, accept: SSE, needs_token: true, headers: &[], retries: true };
        self.open_stream(route, &req).await
    }

    /// A stream from a request the caller built: a query, extra headers, or
    /// no K4 loop (ADR 30.9.26aa D7).
    pub(crate) async fn open_stream<E, B>(&self, route: &StreamRoute, req: &Req<'_, B>) -> Result<EventStream<E>, Error>
    where
        E: DeserializeOwned,
        B: Serialize + ?Sized,
    {
        let res = self.send(req).await?;
        if media_type(res.headers()) != SSE {
            return Err(TransportKind::MalformedResponse.into());
        }
        let served_version = self.inner.versions.observe(res.headers(), req.url);
        let bytes = res.bytes_stream().map(|chunk| chunk.map_err(|e| from_reqwest(e, Phase::Body)));
        Ok(EventStream::new(route, Box::pin(bytes), self.inner.idle, served_version))
    }

    /// `path`, which may carry a query, under the base URL.
    pub(crate) fn url(&self, path: &str) -> String {
        format!("{}{path}", self.inner.base_url)
    }

    pub(crate) async fn json<T: DeserializeOwned>(&self, path: &str, needs_token: bool) -> Result<ApiResponse<T>, Error> {
        let url = self.url(path);
        let req = Req { method: Method::GET, url: &url, body: None::<&()>, accept: JSON, needs_token, headers: &[], retries: true };
        self.json_req(&req).await
    }

    /// A JSON result from a request the caller built.
    pub(crate) async fn json_req<T: DeserializeOwned, B: Serialize + ?Sized>(&self, req: &Req<'_, B>) -> Result<ApiResponse<T>, Error> {
        let res = self.send(req).await?;
        let served_version = self.inner.versions.observe(res.headers(), req.url);
        let bytes = res.bytes().await.map_err(|e| from_reqwest(e, Phase::Body))?;
        let value = serde_json::from_slice(&bytes).map_err(|_| Error::from(TransportKind::MalformedResponse))?;
        Ok(ApiResponse { value, served_version })
    }

    /// A result with no value, for an answer with no body (a `204`; ADR
    /// 1.10.26w D4). Any 2xx is success and its body is never read, so a
    /// later `200 {}` is not a break; `Lingara-Version` is still observed.
    pub(crate) async fn empty_req<B: Serialize + ?Sized>(&self, req: &Req<'_, B>) -> Result<ApiResponse<()>, Error> {
        let res = self.send(req).await?;
        let served_version = self.inner.versions.observe(res.headers(), req.url);
        Ok(ApiResponse { value: (), served_version })
    }

    /// Auth, retries and error mapping; resolves with a 2xx response. A
    /// client with no token source sends an operation that needs one without
    /// `Authorization`, and the server's 401 is the answer.
    async fn send<B: Serialize + ?Sized>(&self, req: &Req<'_, B>) -> Result<reqwest::Response, Error> {
        let res = match (&self.inner.tokens, req.needs_token) {
            (Some(tokens), true) => with_token_retry(tokens.as_ref(), |token| self.attempts(req, Some(token))).await?,
            _ => self.attempts(req, None).await?,
        };
        if res.status().is_success() {
            return Ok(res);
        }
        let headers = res.headers();
        let refusal = Refusal {
            endpoint: Endpoint::V1,
            status: res.status().as_u16(),
            json: is_json(headers),
            retry_after: parse_retry_after(headers, self.inner.policy.clock.now()),
            served_version: headers.get("lingara-version").and_then(|v| v.to_str().ok()).map(str::to_owned),
        };
        Err(from_response(res, refusal).await)
    }

    /// One HTTP request under its own `Retry-After` budget.
    async fn attempts<B: Serialize + ?Sized>(&self, req: &Req<'_, B>, token: Option<AccessToken>) -> Result<reqwest::Response, Error> {
        if !req.retries {
            return self.send_once(req, token.as_ref()).await;
        }
        with_retries(&self.inner.policy, || self.send_once(req, token.as_ref())).await
    }

    async fn send_once<B: Serialize + ?Sized>(&self, req: &Req<'_, B>, token: Option<&AccessToken>) -> Result<reqwest::Response, Error> {
        let inner = &self.inner;
        let mut builder = inner.http.request(req.method.clone(), req.url).header(ACCEPT, req.accept).header(USER_AGENT, &inner.user_agent);
        if let Some(token) = token {
            builder = builder.header(AUTHORIZATION, bearer(token)?);
        }
        if let Some(version) = &inner.version {
            builder = builder.header("lingara-version", version);
        }
        for (name, value) in req.headers {
            builder = builder.header(*name, value);
        }
        if let Some(body) = req.body {
            builder = builder.json(body);
        }
        builder.send().await.map_err(|e| from_reqwest(e, Phase::Send))
    }
}

/// `Bearer <token>`, marked sensitive. A token that cannot be a header value
/// came from a token response that was not well-formed.
fn bearer(token: &AccessToken) -> Result<HeaderValue, Error> {
    let mut value = HeaderValue::try_from(format!("Bearer {}", token.expose_secret())).map_err(|_| Error::from(TransportKind::MalformedResponse))?;
    value.set_sensitive(true);
    Ok(value)
}

/// A path segment, percent-encoded as `encodeURIComponent` would, less its
/// four sub-delimiters: everything but `A–Z a–z 0–9 - . _ ~`.
pub(crate) fn encode_segment(segment: &str) -> String {
    let mut out = String::with_capacity(segment.len());
    for byte in segment.bytes() {
        if byte.is_ascii_alphanumeric() || b"-._~".contains(&byte) {
            out.push(char::from(byte));
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}
