use std::sync::Arc;

use futures_util::StreamExt as _;
use serde_json::{Value, json};

use super::{DialogueFilters, ExportDialogueOptions, ExportedDialogue};
use crate::error::{Error, TransportKind};
use crate::fake_server::{FakeServer, Recorded, Reply};
use crate::models::{RenameDialogueRequest, SaveDialogueDraftRequest};
use crate::{AccessToken, BoxFuture, Client, TokenSource};

const IR: &str = r#"{"checkpoints":[],"facts":[],"id":"night_market_keepers","schema":"dialogue.v1","start":"greet"}"#;
const ETAG: &str = "\"9f2c\"";

/// A token source that never exchanges.
struct Fixed;

impl TokenSource for Fixed {
    fn token(&self) -> BoxFuture<'_, Result<AccessToken, Error>> {
        Box::pin(async { Ok(AccessToken::new("lgr_et_fixed")) })
    }
    fn invalidate(&self, _: &AccessToken) {}
}

fn client(server: &FakeServer) -> Client {
    Client::builder().base_url(&server.url).token_source(Arc::new(Fixed)).build().unwrap()
}

fn etag_only(etag: Option<&str>) -> ExportDialogueOptions {
    ExportDialogueOptions { if_none_match: etag.map(str::to_owned), ..ExportDialogueOptions::default() }
}

fn definition() -> Value {
    json!({"schema": "dialogue.v1", "id": "night_market_keepers", "start": "greet", "x-layout": {"greet": {"x": 1, "y": 2}}})
}

fn dialogue() -> String {
    let draft = json!({"revision": 1, "draft_version": 4, "definition": definition()});
    json!({"id": "d-1", "name": "Market", "created_at": "2026-10-28T09:00:00Z", "updated_at": "2026-10-28T09:00:00Z", "draft": draft, "active": null, "warnings": []})
        .to_string()
}

fn page(id: &str, next: Option<&str>, more: bool) -> String {
    let item = json!({"id": id, "name": "Market", "ir_id": "market", "created_at": "2026-10-28T09:00:00Z", "updated_at": "2026-10-28T09:00:00Z",
        "has_draft": true, "active_revision": null, "npc_count": 1, "checkpoint_count": 2});
    json!({"items": [item], "next_cursor": next, "has_more": more}).to_string()
}

fn body(sent: &Recorded) -> Value {
    serde_json::from_str(&sent.body).unwrap()
}

/// 29.10.26c AC17: through `client.dialogues()`, `PUT` and `PATCH` reach the
/// server with their JSON bodies and `content-type: application/json`; the
/// export answers `Body` with the bytes unparsed and both headers, and
/// `NotModified` on a `304`; `page_dialogues` ends on `has_more: false`, its
/// filters resent.
#[tokio::test]
async fn put_and_patch_are_sent_the_export_reads_both_arms_and_paging_ends() {
    let export_headers = [("content-type", "application/json"), ("etag", ETAG), ("lingara-dialogue-revision", "3")];
    let server = FakeServer::start(move |_, n| match n {
        0..=2 => Reply::json(200, &dialogue()),
        3 => Reply::with(200, &export_headers, IR),
        4 => Reply::with(304, &[("etag", ETAG), ("lingara-dialogue-revision", "3")], ""),
        5 => Reply::json(200, &page("d-1", Some("dl1"), true)),
        _ => Reply::json(200, &page("d-2", None, false)),
    })
    .await;
    let c = client(&server);
    let dialogues = c.dialogues();

    let read = dialogues.get_dialogue("d-1").await.unwrap().into_inner();
    let draft = read.draft.expect("a draft");
    let save = SaveDialogueDraftRequest { definition: draft.definition, draft_version: draft.draft_version, name: None };
    dialogues.save_dialogue_draft("d-1", &save).await.unwrap();
    dialogues.rename_dialogue("d-1", &RenameDialogueRequest { name: "Renamed".into() }).await.unwrap();

    let draft_omit = ExportDialogueOptions { revision: Some("draft".into()), layout: Some("omit".into()), if_none_match: None };
    let exported = dialogues.export_dialogue("d-1", &draft_omit).await.unwrap().into_inner();
    assert_eq!(exported, ExportedDialogue::Body { revision: 3, etag: ETAG.into(), ir_json: IR.into() });
    let unchanged = dialogues.export_dialogue("d-1", &etag_only(Some(ETAG))).await.unwrap().into_inner();
    assert_eq!(unchanged, ExportedDialogue::NotModified { revision: 3, etag: ETAG.into() });

    let filters = DialogueFilters { limit: Some(1), name: Some("market".into()) };
    let ids: Vec<String> = dialogues.page_dialogues(filters).map(|item| item.unwrap().id).collect().await;
    assert_eq!(ids, ["d-1", "d-2"]);

    let sent = server.requests();
    assert_eq!((sent[1].method.as_str(), sent[1].path.as_str()), ("PUT", "/v1/dialogues/d-1/draft"));
    assert_eq!(body(&sent[1]), json!({"definition": definition(), "draft_version": 4}));
    assert_eq!((sent[2].method.as_str(), sent[2].path.as_str()), ("PATCH", "/v1/dialogues/d-1"));
    assert_eq!(body(&sent[2]), json!({"name": "Renamed"}));
    for put_or_patch in &sent[1..=2] {
        assert_eq!(put_or_patch.header("content-type"), Some("application/json"));
    }
    assert_eq!(sent[3].path, "/v1/dialogues/d-1/export?revision=draft&layout=omit");
    assert_eq!(sent[4].header("if-none-match"), Some(ETAG));
    assert_eq!(sent[5].path, "/v1/dialogues?limit=1&name=market");
    assert_eq!(sent[6].path, "/v1/dialogues?cursor=dl1&limit=1&name=market");
    assert_eq!(sent.len(), 7);
}

/// embed 28.10.26i L1: one `GET` with no query, the id one encoded segment,
/// the bearer sent; the body comes back as the bytes the server wrote, with
/// the revision and `ETag` beside it, and no `If-None-Match` unless asked.
#[tokio::test]
async fn an_export_returns_the_bytes_and_both_headers() {
    let headers = [("content-type", "application/json"), ("etag", ETAG), ("lingara-dialogue-revision", "3"), ("lingara-version", "2026-10-simple-tarpon")];
    let server = FakeServer::start(move |_, _| Reply::with(200, &headers, IR)).await;
    let exported = client(&server).dialogues().export_dialogue("night market/1", &etag_only(None)).await.unwrap();
    assert_eq!(exported.served_version(), Some("2026-10-simple-tarpon"));
    assert_eq!(exported.into_inner(), ExportedDialogue::Body { revision: 3, etag: ETAG.into(), ir_json: IR.into() });
    let sent = &server.requests()[0];
    assert_eq!((sent.method.as_str(), sent.path.as_str()), ("GET", "/v1/dialogues/night%20market%2F1/export"));
    assert_eq!((sent.header("authorization"), sent.header("if-none-match")), (Some("Bearer lgr_et_fixed"), None));
}

/// embed 28.10.26i L2: the held `etag` goes out as `If-None-Match`, and a
/// `304` is `NotModified` with both headers, never an error.
#[tokio::test]
async fn a_matching_etag_is_not_modified() {
    let server = FakeServer::start(|_, _| Reply::with(304, &[("etag", ETAG), ("lingara-dialogue-revision", "3")], "")).await;
    let exported = client(&server).dialogues().export_dialogue("keepers", &etag_only(Some(ETAG))).await.unwrap();
    assert_eq!(exported.into_inner(), ExportedDialogue::NotModified { revision: 3, etag: ETAG.into() });
    assert_eq!(server.requests()[0].header("if-none-match"), Some(ETAG));
}

/// embed 28.10.26i D6: a `200` without `Lingara-Dialogue-Revision` is
/// `MalformedResponse`; a `404 dialogue_not_active` stays an `ApiError`.
#[tokio::test]
async fn a_missing_revision_is_malformed_and_a_404_is_refused() {
    let server = FakeServer::start(|_, n| match n {
        0 => Reply::with(200, &[("content-type", "application/json"), ("etag", ETAG)], IR),
        _ => Reply::json(404, r#"{"code":"dialogue_not_active","error":"No active revision."}"#),
    })
    .await;
    let c = client(&server);
    let err = c.dialogues().export_dialogue("keepers", &etag_only(None)).await.unwrap_err();
    assert!(matches!(&err, Error::Transport(e) if e.kind == TransportKind::MalformedResponse), "{err:?}");
    let err = c.dialogues().export_dialogue("keepers", &etag_only(None)).await.unwrap_err();
    assert!(matches!(&err, Error::Api(e) if e.status == 404 && e.code == "dialogue_not_active"), "{err:?}");
}
