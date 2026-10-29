//! Canvases (ADR 16.10.26ak): the seven `/v1/canvases` operations and the
//! page helper, on a group reached as `client.canvases()`. The group holds
//! no state of its own: it borrows the client, so the token, retries,
//! version pin and errors are the client's.
//!
//! A stale `draft_version` is `409 canvas_conflict`, raised as an
//! [`ApiError`](crate::ApiError) and never retried: re-read with
//! `get_canvas`, compare drafts, and decide. Branch on an error's `code`,
//! never its localised `message`. A dry-run answers `200` whether or not
//! `ok` is true; a `refused` verdict's `code` and English `error` are data.
//! There is no delete: a canvas is deleted through the platform's
//! lifecycle routes, not this library.

use std::collections::VecDeque;

use futures_util::stream::{self, BoxStream, StreamExt as _};
use reqwest::Method;
use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::client::{ApiResponse, Client};
use crate::error::{Error, TransportKind};
use crate::events::with_query;
use crate::models::{
    ActivateCanvasRequest, Canvas, CanvasDryRun, CanvasPage, CanvasPlan, CanvasSummary, CreateCanvasRequest, RenameCanvasRequest,
    SaveCanvasDraftRequest,
};
use crate::pipeline::{Req, encode_segment};

/// What `list_canvases` filters by, and what `page_canvases` resends on
/// every page. The lists are sent comma-separated, one value each.
#[derive(Clone, Debug, Default)]
pub struct CanvasFilters {
    /// The page size, 1–50.
    pub limit: Option<u8>,
    /// A substring of the canvas name.
    pub name: Option<String>,
    /// `all`, `live`, `changed` or `unapplied`.
    pub status: Option<String>,
    pub recipes: Vec<String>,
    pub kinds: Vec<String>,
    pub scopes: Vec<String>,
}

/// `list_canvases`' query: the filters and the page to start from.
#[derive(Clone, Debug, Default)]
pub struct ListCanvasesParams {
    /// A previous page's `next_cursor`.
    pub cursor: Option<String>,
    pub filters: CanvasFilters,
}

/// Every canvas the filters match, one page at a time; see
/// [`Canvases::page_canvases`].
pub type CanvasItems = BoxStream<'static, Result<CanvasSummary, Error>>;

/// The canvas operations, borrowed from a [`Client`].
#[derive(Clone, Copy, Debug)]
pub struct Canvases<'a> {
    client: &'a Client,
}

impl Client {
    /// The canvas operations (`canvas:read`, `canvas:write`).
    pub fn canvases(&self) -> Canvases<'_> {
        Canvases { client: self }
    }
}

impl Canvases<'_> {
    /// One page of canvases (`GET /v1/canvases`); `page_canvases` walks them.
    pub async fn list_canvases(&self, params: &ListCanvasesParams) -> Result<ApiResponse<CanvasPage>, Error> {
        self.client.json(&with_query("/v1/canvases", &query(params)), true).await
    }

    /// Creates a canvas (`POST /v1/canvases`, `201`). The answer does not
    /// echo `recipe`.
    pub async fn create_canvas(&self, body: &CreateCanvasRequest) -> Result<ApiResponse<Canvas>, Error> {
        self.send(Method::POST, "/v1/canvases".into(), Some(body)).await
    }

    /// One canvas, its draft and active revision inline (`GET`).
    pub async fn get_canvas(&self, canvas_id: &str) -> Result<ApiResponse<Canvas>, Error> {
        self.send(Method::GET, path(canvas_id, ""), None::<&()>).await
    }

    /// Renames a canvas (`PATCH /v1/canvases/{canvas_id}`).
    pub async fn rename_canvas(&self, canvas_id: &str, body: &RenameCanvasRequest) -> Result<ApiResponse<Canvas>, Error> {
        self.send(Method::PATCH, path(canvas_id, ""), Some(body)).await
    }

    /// Saves the draft (`PUT …/draft`). A stale `draft_version` is `409
    /// canvas_conflict`, which may follow your own retried save: re-read
    /// with `get_canvas` and compare before you decide.
    pub async fn save_canvas_draft(&self, canvas_id: &str, body: &SaveCanvasDraftRequest) -> Result<ApiResponse<Canvas>, Error> {
        self.send(Method::PUT, path(canvas_id, "/draft"), Some(body)).await
    }

    /// Promotes a draft (`POST …/activate`) with the objects an apply made.
    /// A replay at a `draft_version` already promoted is `409
    /// canvas_conflict` and writes nothing.
    pub async fn activate_canvas(&self, canvas_id: &str, body: &ActivateCanvasRequest) -> Result<ApiResponse<Canvas>, Error> {
        self.send(Method::POST, path(canvas_id, "/activate"), Some(body)).await
    }

    /// Judges a plan without applying it (`POST /v1/canvases/dry-run`). A
    /// plan with a refused op still answers `200`, `ok: false`.
    pub async fn dry_run_canvas_plan(&self, body: &CanvasPlan) -> Result<ApiResponse<CanvasDryRun>, Error> {
        self.send(Method::POST, "/v1/canvases/dry-run".into(), Some(body)).await
    }

    /// Every canvas the filters match, following `next_cursor` until a page
    /// says `has_more: false`, the filters resent on every page. It never
    /// sleeps or polls, and ends on the first error. A page with `has_more`
    /// and no `next_cursor` is `malformed_response`, its items unyielded.
    pub fn page_canvases(&self, filters: CanvasFilters) -> CanvasItems {
        let start = Pages { client: self.client.clone(), params: ListCanvasesParams { cursor: None, filters }, items: VecDeque::new(), more: true };
        stream::try_unfold(start, next_item).boxed()
    }

    async fn send<B: Serialize + ?Sized, T: DeserializeOwned>(&self, method: Method, path: String, body: Option<&B>) -> Result<ApiResponse<T>, Error> {
        let url = self.client.url(&path);
        let req = Req { method, url: &url, body, accept: "application/json", needs_token: true, headers: &[], retries: true };
        self.client.json_req(&req).await
    }
}

struct Pages {
    client: Client,
    params: ListCanvasesParams,
    items: VecDeque<CanvasSummary>,
    more: bool,
}

async fn next_item(mut pages: Pages) -> Result<Option<(CanvasSummary, Pages)>, Error> {
    loop {
        if let Some(item) = pages.items.pop_front() {
            return Ok(Some((item, pages)));
        }
        if !pages.more {
            return Ok(None);
        }
        let page = pages.client.canvases().list_canvases(&pages.params).await?.into_inner();
        if page.has_more && page.next_cursor.is_none() {
            return Err(TransportKind::MalformedResponse.into());
        }
        (pages.items, pages.more, pages.params.cursor) = (page.items.into(), page.has_more, page.next_cursor);
    }
}

fn path(canvas_id: &str, rest: &str) -> String {
    format!("/v1/canvases/{}{rest}", encode_segment(canvas_id))
}

fn query(params: &ListCanvasesParams) -> String {
    let f = &params.filters;
    let mut q = form_urlencoded::Serializer::new(String::new());
    let lists = [("recipes", &f.recipes), ("kinds", &f.kinds), ("scopes", &f.scopes)];
    let single = [("cursor", params.cursor.clone()), ("limit", f.limit.map(|n| n.to_string())), ("name", f.name.clone()), ("status", f.status.clone())];
    for (key, value) in single {
        if let Some(value) = value {
            q.append_pair(key, &value);
        }
    }
    for (key, list) in lists.into_iter().filter(|(_, l)| !l.is_empty()) {
        q.append_pair(key, &list.join(","));
    }
    q.finish()
}

#[cfg(test)]
#[path = "tests/canvas_tests.rs"]
mod tests;
