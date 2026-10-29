//! `exportDialogue` (ADR 29.10.26c D6): the canonical bytes and two headers,
//! or `NotModified` on a `304`.

use reqwest::header::{ETAG, HeaderMap};
use reqwest::{Method, StatusCode};

use super::{Dialogues, path};
use crate::client::ApiResponse;
use crate::error::{Error, Phase, TransportKind, from_reqwest};
use crate::events::with_query;
use crate::pipeline::Req;

/// What [`Dialogues::export_dialogue`] sends beyond the id; each is sent
/// only when set, so the default is the active revision with its layout.
#[derive(Clone, Debug, Default)]
pub struct ExportDialogueOptions {
    /// `active` (the server's default) or `draft`.
    pub revision: Option<String>,
    /// `keep` (the server's default) or `omit`, which drops every `x-`
    /// member and has its own `ETag`.
    pub layout: Option<String>,
    /// The `etag` of the copy you hold, sent back exactly as received.
    pub if_none_match: Option<String>,
}

/// What [`Dialogues::export_dialogue`] answers.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ExportedDialogue {
    /// The `dialogue.v1` document, unparsed: its bytes are canonical, and
    /// `etag` is their SHA-256.
    Body { revision: u64, etag: String, ir_json: String },
    /// Unchanged since the `etag` sent as `If-None-Match`: keep what you have.
    NotModified { revision: u64, etag: String },
}

impl Dialogues<'_> {
    /// Exports a dialogue (`GET /v1/dialogues/{dialogue_id}/export`; scope
    /// `dialogues:read`, which an embed token can carry for the active
    /// revision). Pass the `etag` of the copy you hold as `if_none_match`,
    /// and an unchanged revision answers [`ExportedDialogue::NotModified`]
    /// with no body; a `304` still spends one unit of the rate budget. No
    /// active revision is `404 dialogue_not_active`, raised as an
    /// `ApiError`. K4's `Retry-After` loop applies: the export only reads.
    ///
    /// The body is returned as text and never parsed here, so the bytes you
    /// hold are the bytes the `etag` names.
    pub async fn export_dialogue(&self, dialogue_id: &str, options: &ExportDialogueOptions) -> Result<ApiResponse<ExportedDialogue>, Error> {
        let url = self.client.url(&with_query(&path(dialogue_id, "/export"), &query(options)));
        let headers: Vec<(&'static str, String)> = options.if_none_match.iter().map(|etag| ("if-none-match", etag.clone())).collect();
        let req = Req { method: Method::GET, url: &url, body: None::<&()>, accept: "application/json", needs_token: true, headers: &headers, retries: true };
        let res = self.client.send_allowing(&req, Some(StatusCode::NOT_MODIFIED)).await?;
        let served_version = self.client.inner.versions.observe(res.headers(), req.url);
        let (revision, etag) = revision_and_etag(res.headers())?;
        if res.status() == StatusCode::NOT_MODIFIED {
            return Ok(ApiResponse { value: ExportedDialogue::NotModified { revision, etag }, served_version });
        }
        let bytes = res.bytes().await.map_err(|e| from_reqwest(e, Phase::Body))?;
        let ir_json = String::from_utf8(bytes.to_vec()).map_err(|_| Error::from(TransportKind::MalformedResponse))?;
        Ok(ApiResponse { value: ExportedDialogue::Body { revision, etag, ir_json }, served_version })
    }
}

/// The query in the spec's parameter order, `revision` then `layout`.
fn query(options: &ExportDialogueOptions) -> String {
    let mut q = form_urlencoded::Serializer::new(String::new());
    for (key, value) in [("revision", &options.revision), ("layout", &options.layout)] {
        if let Some(value) = value {
            q.append_pair(key, value);
        }
    }
    q.finish()
}

/// `Lingara-Dialogue-Revision` and `ETag`, which the server sends on a `200`
/// and a `304` alike: either missing, or a revision that is not a
/// non-negative integer, is `MalformedResponse`.
fn revision_and_etag(headers: &HeaderMap) -> Result<(u64, String), Error> {
    let malformed = || Error::from(TransportKind::MalformedResponse);
    let text = |name: &str| headers.get(name).and_then(|v| v.to_str().ok());
    let revision = text("lingara-dialogue-revision").and_then(|v| v.parse().ok()).ok_or_else(malformed)?;
    let etag = text(ETAG.as_str()).ok_or_else(malformed)?.to_owned();
    Ok((revision, etag))
}
