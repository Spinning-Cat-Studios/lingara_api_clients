use std::sync::Arc;

use futures_util::StreamExt as _;
use serde_json::{Value, json};

use super::CanvasFilters;
use crate::error::{Error, TransportKind};
use crate::fake_server::{FakeServer, Reply};
use crate::models::{RenameCanvasRequest, SaveCanvasDraftRequest};
use crate::{AccessToken, BoxFuture, Client, TokenSource};

struct Fixed;

impl TokenSource for Fixed {
    fn token(&self) -> BoxFuture<'_, Result<AccessToken, Error>> {
        Box::pin(async { Ok(AccessToken::new("lgr_at_fixed")) })
    }
    fn invalidate(&self, _: &AccessToken) {}
}

fn client(server: &FakeServer) -> Client {
    Client::builder().base_url(&server.url).token_source(Arc::new(Fixed)).build().unwrap()
}

fn definition() -> Value {
    json!({"schema": 2, "nodes": [{"id": "n1", "kind": "client", "position": [0.5, 1.5], "fields": {"name": "Lesson server", "x_editor_hint": {"collapsed": true}}}]})
}

fn canvas() -> String {
    let draft = json!({"revision": 1, "draft_version": 4, "definition": definition(), "objects": {}});
    json!({"id": "c-1", "name": "Hooks", "created_at": "2026-10-20T09:00:00Z", "updated_at": "2026-10-20T09:00:00Z", "draft": draft, "active": null}).to_string()
}

fn page(id: &str, next: Option<&str>, more: bool) -> String {
    let item = json!({"id": id, "name": "Hooks", "recipe": "blank", "created_at": "2026-10-20T09:00:00Z", "updated_at": "2026-10-20T09:00:00Z",
        "has_draft": true, "active_revision": null, "node_count": 1, "node_kinds": ["client"], "scopes": [], "objects": []});
    json!({"items": [item], "total": null, "next_cursor": next, "has_more": more}).to_string()
}

fn body(sent: &crate::fake_server::Recorded) -> Value {
    serde_json::from_str(&sent.body).unwrap()
}

/// 16.10.26ak AC10: through `client.canvases()`, `PUT` and `PATCH` reach the
/// server with their JSON bodies and `content-type: application/json`; a
/// definition read then saved keeps an unknown `fields` key; and
/// `page_canvases` ends on `has_more: false`, its filters resent, and on a
/// page with `has_more` but no cursor raises `malformed_response`.
#[tokio::test]
async fn put_and_patch_are_sent_and_paging_ends() {
    let server = FakeServer::start(|_, n| match n {
        0..=2 => Reply::json(200, &canvas()),
        3 => Reply::json(200, &page("c-1", Some("cv1"), true)),
        4 => Reply::json(200, &page("c-2", None, false)),
        _ => Reply::json(200, &page("c-3", None, true)),
    })
    .await;
    let c = client(&server);
    let canvases = c.canvases();

    let read = canvases.get_canvas("c-1").await.unwrap().into_inner();
    let draft = read.draft.expect("a draft");
    let save = SaveCanvasDraftRequest { definition: draft.definition, draft_version: draft.draft_version, name: None };
    canvases.save_canvas_draft("c-1", &save).await.unwrap();
    canvases.rename_canvas("c-1", &RenameCanvasRequest { name: "Renamed".into() }).await.unwrap();

    let filters = CanvasFilters { limit: Some(1), kinds: vec!["client".into(), "webhook".into()], ..CanvasFilters::default() };
    let ids: Vec<String> = canvases.page_canvases(filters.clone()).map(|item| item.unwrap().id).collect().await;
    assert_eq!(ids, ["c-1", "c-2"]);
    let mut broken = canvases.page_canvases(CanvasFilters::default());
    let err = broken.next().await.unwrap().unwrap_err();
    assert!(matches!(&err, Error::Transport(e) if e.kind == TransportKind::MalformedResponse), "{err:?}");
    assert!(broken.next().await.is_none());

    let sent = server.requests();
    assert_eq!((sent[1].method.as_str(), sent[1].path.as_str()), ("PUT", "/v1/canvases/c-1/draft"));
    assert_eq!(body(&sent[1]), json!({"definition": definition(), "draft_version": 4}));
    assert_eq!((sent[2].method.as_str(), sent[2].path.as_str()), ("PATCH", "/v1/canvases/c-1"));
    assert_eq!(body(&sent[2]), json!({"name": "Renamed"}));
    for put_or_patch in &sent[1..=2] {
        assert_eq!(put_or_patch.header("content-type"), Some("application/json"));
    }
    assert_eq!(sent[3].path, "/v1/canvases?limit=1&kinds=client%2Cwebhook");
    assert_eq!(sent[4].path, "/v1/canvases?cursor=cv1&limit=1&kinds=client%2Cwebhook");
    assert_eq!(sent.len(), 6);
}
