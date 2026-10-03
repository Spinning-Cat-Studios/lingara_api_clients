use std::sync::Arc;

use super::{EventStart, EventsOptions, InboundEvent, SendEventOptions, parse_event, query, uuid_v4};
use crate::events::Event;
use crate::fake_server::{FakeServer, Reply};
use crate::{AccessToken, BoxFuture, Client, Error, TokenSource};

const READY: &str = r#"{"id":"lgr_evt_1","type":"lesson_plan.ready","created_at":"2026-10-01T09:12:44Z","api_version":"2026-09-equipped-boxfish","subject":"lgr_sub_1","data":{"plan_id":"p1","status":"complete","title":null,"source_lang":"en","target_lang":"zh","level":2}}"#;

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

/// 30.9.26aa D3: a known type decodes into its arm, an unknown one is
/// `Unknown` with its raw `data`, and a known type whose `data` does not
/// decode, or a body that is not an envelope, is an error.
#[test]
fn parse_event_routes_on_type() {
    let ready = parse_event(READY.as_bytes()).unwrap();
    assert!(matches!(&ready, Event::LessonPlanReady(e) if e.data.plan_id == "p1"));
    assert_eq!((ready.id(), ready.event_type(), ready.subject()), ("lgr_evt_1", "lesson_plan.ready", "lgr_sub_1"));

    let archived = READY.replace("lesson_plan.ready", "lesson_plan.archived");
    let unknown = parse_event(archived.as_bytes()).unwrap();
    assert!(matches!(&unknown, Event::Unknown(e) if e.data["plan_id"] == "p1"));
    assert_eq!(unknown.event_type(), "lesson_plan.archived");

    assert!(parse_event(READY.replace(r#""plan_id":"p1""#, r#""plan_id":5"#).as_bytes()).is_err());
    assert!(parse_event(br#"{"test": 1}"#).is_err());
}

/// 30.9.26aa D8: a generated key is a lower-case UUIDv4, and each call
/// draws a new one.
#[test]
fn a_generated_key_is_a_uuid_v4() {
    let key = uuid_v4().unwrap();
    let groups: Vec<&str> = key.split('-').collect();
    assert_eq!(groups.iter().map(|g| g.len()).collect::<Vec<_>>(), [8, 4, 4, 4, 12], "{key}");
    assert!(key.bytes().all(|b| b == b'-' || b.is_ascii_digit() || (b'a'..=b'f').contains(&b)), "{key}");
    assert!(groups[2].starts_with('4') && groups[3].starts_with(['8', '9', 'a', 'b']), "{key}");
    assert_ne!(key, uuid_v4().unwrap());
}

/// `types` is one comma-separated value (`explode: false`).
#[test]
fn the_query_joins_types_into_one_value() {
    let types = vec!["lesson_plan.ready".to_owned(), "lesson_plan.failed".to_owned()];
    let q = query(Some("c 1"), Some(EventStart::Oldest), &types, Some(2));
    assert_eq!(q, "cursor=c+1&start=oldest&types=lesson_plan.ready%2Clesson_plan.failed&limit=2");
    assert_eq!(query(None, None, &[], None), "");
}

/// 30.9.26aa D6: the feed walks pages, `cursor` moves to a page's next
/// cursor when its last item goes out, and `start` is not sent once a
/// cursor exists.
#[tokio::test]
async fn the_feed_walks_pages_and_moves_its_cursor() {
    let server = FakeServer::start(|_, n| match n {
        0 => Reply::json(200, &format!(r#"{{"items":[{READY},{READY}],"next_cursor":"c1","has_more":true}}"#)),
        _ => Reply::json(200, r#"{"items":[],"next_cursor":"c2","has_more":false}"#),
    })
    .await;
    let options = EventsOptions { start: Some(EventStart::Oldest), ..EventsOptions::default() };
    let mut feed = client(&server).events(options);
    assert!(feed.next().await.unwrap().is_ok());
    assert_eq!(feed.cursor(), None, "mid-page");
    assert!(feed.next().await.unwrap().is_ok());
    assert_eq!(feed.cursor(), Some("c1"));
    assert!(feed.next().await.is_none());
    assert_eq!(feed.cursor(), Some("c2"), "an empty page still advances");
    let paths: Vec<String> = server.requests().into_iter().map(|r| r.path).collect();
    assert_eq!(paths, ["/v1/events?start=oldest", "/v1/events?cursor=c1"]);
}

/// 30.9.26aa D8: the body is `{type, data}`, and a caller's key is sent
/// unchanged.
#[tokio::test]
async fn send_event_posts_type_and_data_with_the_key() {
    let accepted = r#"{"id":"lgr_evt_9","type":"world.practice_requested","created_at":"2026-10-01T09:12:44Z"}"#;
    let server = FakeServer::start(move |_, _| Reply::json(202, accepted)).await;
    let event: InboundEvent = serde_json::from_str(r#"{"type":"world.practice_requested","data":{"topic":"Directions","source_lang":"en","target_lang":"zh","level":2}}"#).unwrap();
    let options = SendEventOptions { idempotency_key: Some("save-17".into()) };
    let answer = client(&server).send_event(&event, options).await.unwrap();
    assert_eq!(answer.id, "lgr_evt_9");
    let request = &server.requests()[0];
    assert_eq!((request.method.as_str(), request.path.as_str()), ("POST", "/v1/events"));
    assert_eq!(request.header("idempotency-key"), Some("save-17"));
    let body: serde_json::Value = serde_json::from_str(&request.body).unwrap();
    assert_eq!(body["type"], "world.practice_requested");
    assert_eq!(body["data"]["topic"], "Directions");
}
