//! Stored dialogues (Backend ADR 28.10.26h; clients ADR 29.10.26c): the
//! seven `/v1/dialogues` operations and the page helper, on a group reached
//! as `client.dialogues()`. The group holds no state of its own: it borrows
//! the client, so the token, retries, version pin and errors are the
//! client's.
//!
//! A stale `draft_version` is `409 dialogue_conflict`, raised as an
//! [`ApiError`](crate::ApiError) and never retried: re-read with
//! `get_dialogue`, take `draft.draft_version` (or `0` when there is no
//! draft), and decide. Branch on an error's `code`, never its localised
//! `message`. The export (`export.rs`) is hand-written for its bytes, its
//! two headers and its `304`. There is no delete: a dialogue is deleted
//! through the platform's lifecycle routes, not this library.

mod export;

use std::collections::VecDeque;

use futures_util::stream::{self, BoxStream, StreamExt as _};
use reqwest::Method;
use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::client::{ApiResponse, Client};
use crate::error::{Error, TransportKind};
use crate::events::with_query;
use crate::models::{ActivateDialogueRequest, CreateDialogueRequest, Dialogue, DialoguePage, DialogueSummary, RenameDialogueRequest, SaveDialogueDraftRequest};
use crate::pipeline::{Req, encode_segment};

pub use export::{ExportDialogueOptions, ExportedDialogue};

/// What `list_dialogues` filters by, and what `page_dialogues` resends on
/// every page.
#[derive(Clone, Debug, Default)]
pub struct DialogueFilters {
    /// The page size, 1–50.
    pub limit: Option<u8>,
    /// A substring of the dialogue name.
    pub name: Option<String>,
}

/// `list_dialogues`' query: the filters and the page to start from.
#[derive(Clone, Debug, Default)]
pub struct ListDialoguesParams {
    /// A previous page's `next_cursor`.
    pub cursor: Option<String>,
    pub filters: DialogueFilters,
}

/// Every dialogue the filters match, one page at a time; see
/// [`Dialogues::page_dialogues`].
pub type DialogueItems = BoxStream<'static, Result<DialogueSummary, Error>>;

/// The dialogue operations, borrowed from a [`Client`].
#[derive(Clone, Copy, Debug)]
pub struct Dialogues<'a> {
    client: &'a Client,
}

impl Client {
    /// The dialogue operations (`dialogues:read`, `dialogues:write`).
    pub fn dialogues(&self) -> Dialogues<'_> {
        Dialogues { client: self }
    }
}

impl Dialogues<'_> {
    /// One page of dialogues (`GET /v1/dialogues`); `page_dialogues` walks
    /// them.
    pub async fn list_dialogues(&self, params: &ListDialoguesParams) -> Result<ApiResponse<DialoguePage>, Error> {
        self.client.json(&with_query("/v1/dialogues", &query(params)), true).await
    }

    /// Creates a dialogue (`POST /v1/dialogues`, `201`). Its `warnings` are
    /// advice, never a refusal.
    pub async fn create_dialogue(&self, body: &CreateDialogueRequest) -> Result<ApiResponse<Dialogue>, Error> {
        self.send(Method::POST, "/v1/dialogues".into(), Some(body)).await
    }

    /// One dialogue, its draft and active revision inline (`GET`).
    pub async fn get_dialogue(&self, dialogue_id: &str) -> Result<ApiResponse<Dialogue>, Error> {
        self.send(Method::GET, path(dialogue_id, ""), None::<&()>).await
    }

    /// Renames a dialogue (`PATCH /v1/dialogues/{dialogue_id}`).
    pub async fn rename_dialogue(&self, dialogue_id: &str, body: &RenameDialogueRequest) -> Result<ApiResponse<Dialogue>, Error> {
        self.send(Method::PATCH, path(dialogue_id, ""), Some(body)).await
    }

    /// Saves the draft (`PUT …/draft`); `draft_version: 0` with no draft
    /// opens one. A stale `draft_version` is `409 dialogue_conflict`, which
    /// may follow your own retried save: re-read with `get_dialogue` and
    /// compare before you decide.
    pub async fn save_dialogue_draft(&self, dialogue_id: &str, body: &SaveDialogueDraftRequest) -> Result<ApiResponse<Dialogue>, Error> {
        self.send(Method::PUT, path(dialogue_id, "/draft"), Some(body)).await
    }

    /// Promotes the draft (`POST …/activate`). A stale or missing draft is
    /// `409 dialogue_conflict` and writes nothing.
    pub async fn activate_dialogue(&self, dialogue_id: &str, body: &ActivateDialogueRequest) -> Result<ApiResponse<Dialogue>, Error> {
        self.send(Method::POST, path(dialogue_id, "/activate"), Some(body)).await
    }

    /// Every dialogue the filters match, following `next_cursor` until a
    /// page says `has_more: false`, the filters resent on every page. It
    /// never sleeps or polls, and ends on the first error. A page with
    /// `has_more` and no `next_cursor` is `malformed_response`, its items
    /// unyielded.
    pub fn page_dialogues(&self, filters: DialogueFilters) -> DialogueItems {
        let start = Pages { client: self.client.clone(), params: ListDialoguesParams { cursor: None, filters }, items: VecDeque::new(), more: true };
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
    params: ListDialoguesParams,
    items: VecDeque<DialogueSummary>,
    more: bool,
}

async fn next_item(mut pages: Pages) -> Result<Option<(DialogueSummary, Pages)>, Error> {
    loop {
        if let Some(item) = pages.items.pop_front() {
            return Ok(Some((item, pages)));
        }
        if !pages.more {
            return Ok(None);
        }
        let page = pages.client.dialogues().list_dialogues(&pages.params).await?.into_inner();
        if page.has_more && page.next_cursor.is_none() {
            return Err(TransportKind::MalformedResponse.into());
        }
        (pages.items, pages.more, pages.params.cursor) = (page.items.into(), page.has_more, page.next_cursor);
    }
}

fn path(dialogue_id: &str, rest: &str) -> String {
    format!("/v1/dialogues/{}{rest}", encode_segment(dialogue_id))
}

fn query(params: &ListDialoguesParams) -> String {
    let f = &params.filters;
    let mut q = form_urlencoded::Serializer::new(String::new());
    let pairs = [("cursor", params.cursor.clone()), ("limit", f.limit.map(|n| n.to_string())), ("name", f.name.clone())];
    for (key, value) in pairs {
        if let Some(value) = value {
            q.append_pair(key, &value);
        }
    }
    q.finish()
}

#[cfg(test)]
#[path = "../tests/dialogues_tests.rs"]
mod tests;
