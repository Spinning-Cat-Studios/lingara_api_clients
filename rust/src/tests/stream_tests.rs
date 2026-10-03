use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::task::Poll;
use std::time::Duration;

use bytes::Bytes;
use futures_util::StreamExt as _;
use futures_util::stream;

use super::{Body, EventStream};
use crate::error::{Error, TransportKind};
use crate::fake_server::{FakeServer, Reply};
use crate::generated::streams::{GENERATE_VOCABULARY, ROUTES};
use crate::events::StreamEventsParams;
use crate::models::{CreateLessonPlanEvent, GenerateVocabularyEvent, SendTutorMessageEvent, StreamEventsEvent, StreamLessonPlanEvent, VocabRequest};
use crate::{AccessToken, BoxFuture, Client, TokenSource};

const STARTED: &str = "event: started\ndata: {\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\",\"framework\":\"HSK\",\"count\":1,\"ai_generated\":true}}\n\n";
const ITEM: &str = "event: item\ndata: {\"word\":\"你好\",\"translation\":\"hello\"}\n\n";
const DONE: &str = "event: done\ndata: {}\n\n";
const PLAN_STARTED: &str = "event: started\ndata: {\"plan_id\":\"p1\"}\n\n";
const PHASE: &str = "event: phase\ndata: {\"phase\":\"selecting_vocabulary\",\"attempt\":1}\n\n";
const RESULT: &str = "event: result\ndata: {\"plan\":{\"id\":\"p1\",\"status\":\"complete\",\"source_lang\":\"en\",\"target_lang\":\"zh\",\"level\":2,\"created_at\":\"2026-09-23T10:00:00Z\",\"ai_generated\":true}}\n\n";
const PENDING: &str = "event: pending\ndata: {\"plan_id\":\"p1\",\"status\":\"generating\"}\n\n";
const DELTA: &str = "event: delta\ndata: {\"text\":\"你好\"}\n\n";
const ENVELOPE: &str = "id: c1\nevent: event\ndata: {\"id\":\"lgr_evt_1\",\"type\":\"lesson_plan.archived\",\"created_at\":\"2026-10-01T09:12:44Z\",\"api_version\":\"2026-09-equipped-boxfish\",\"subject\":\"lgr_sub_1\",\"data\":{}}\n\n";

/// A token source that never exchanges.
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

fn vocab() -> VocabRequest {
    VocabRequest { level: 2, source_lang: "en".into(), target_lang: "zh".into(), count: None }
}

/// An in-memory body: each chunk after its delay, then the end.
fn body(script: Vec<(u64, &'static str)>) -> Body {
    Box::pin(stream::iter(script).then(|(delay, chunk)| async move {
        tokio::time::sleep(Duration::from_millis(delay)).await;
        Ok::<_, Error>(Bytes::from_static(chunk.as_bytes()))
    }))
}

fn vocab_stream(body: Body, idle_ms: u64) -> EventStream<GenerateVocabularyEvent> {
    EventStream::new(&GENERATE_VOCABULARY, body, Duration::from_millis(idle_ms), Some("2026-09-knowing-tenpounder".into()))
}

fn kind(item: Option<Result<GenerateVocabularyEvent, Error>>) -> Option<TransportKind> {
    match item {
        Some(Err(Error::Transport(e))) => Some(e.kind),
        _ => None,
    }
}

/// 29.9.26p AC9: dropping an `EventStream` mid-stream closes the
/// connection, which a local listener sees as EOF within 2 s.
#[tokio::test]
async fn drop_closes_the_connection() {
    let mut server = FakeServer::start(|_, _| Reply::sse(&[], &[STARTED], true)).await;
    let mut events = client(&server).generate_vocabulary(&vocab()).await.unwrap();
    assert!(matches!(events.next().await, Some(Ok(GenerateVocabularyEvent::Started(_)))));
    drop(events);
    assert!(server.disconnected_within(Duration::from_secs(2)).await);
}

/// 29.9.26p AC10: under paused time with `stream_idle_timeout` at 50 ms, a
/// silence of 50 ms while a poll is pending is `Timeout`, a keepalive every
/// 30 ms keeps the stream open, and holding an event for 100 ms costs
/// nothing.
#[tokio::test(start_paused = true)]
async fn idle_timeout_is_an_option_and_a_keepalive_resets_it() {
    let mut silent = vocab_stream(body(vec![(0, STARTED), (60, ITEM)]), 50);
    assert!(matches!(silent.next().await, Some(Ok(GenerateVocabularyEvent::Started(_)))));
    assert_eq!(kind(silent.next().await), Some(TransportKind::Timeout));
    assert!(silent.next().await.is_none());

    let keepalive = ": keepalive\n\n";
    let script = vec![(0, STARTED), (30, keepalive), (30, keepalive), (30, keepalive), (30, ITEM), (0, DONE)];
    let mut kept = vocab_stream(body(script), 50);
    assert!(matches!(kept.next().await, Some(Ok(GenerateVocabularyEvent::Started(_)))));
    assert!(matches!(kept.next().await, Some(Ok(GenerateVocabularyEvent::Item(_)))));
    assert!(kept.next().await.is_none());

    let mut held = vocab_stream(body(vec![(0, STARTED), (0, ITEM), (0, DONE)]), 50);
    assert!(matches!(held.next().await, Some(Ok(GenerateVocabularyEvent::Started(_)))));
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert!(matches!(held.next().await, Some(Ok(GenerateVocabularyEvent::Item(_)))));
    assert!(held.next().await.is_none());
}

/// Drains a stream into its events' tags (`event` names).
async fn tags<E: serde::Serialize + serde::de::DeserializeOwned>(mut events: EventStream<E>) -> Vec<String> {
    let mut out = Vec::new();
    while let Some(event) = events.next().await {
        let value = serde_json::to_value(event.expect("no error")).unwrap();
        out.push(value["event"].as_str().unwrap().to_owned());
    }
    out
}

/// 29.9.26p AC11, 29.9.26ai AC9: `stream.rs` holds no terminal table, and
/// each stream operation ends on its own terminal (`result` and `pending`
/// yielded, `done` not). That each route's `ends` matches the view is
/// xtask's `each_stream_ends_on_the_terminals_the_view_names` plus
/// `check-codegen-rust`: this file ships in the packaged crate, which carries
/// no `spec/`, so it cannot read the view (the MSRV job builds exactly that).
#[tokio::test]
async fn each_operation_ends_on_its_own_terminal() {
    let source = include_str!("../stream.rs");
    assert!(!source.contains("TERMINALS") && !source.contains("\"pending\"") && !source.contains("\"result\""));
    for route in ROUTES {
        // 30.9.26aa D7: the one tail is the only resumable route.
        assert_eq!(route.resumable, route.operation_id == "streamEvents", "{}", route.operation_id);
        // Each script carries bytes past its terminal, which must never surface.
        let script: &[&str] = match route.operation_id {
            "generateVocabulary" => &[STARTED, ITEM, DONE, ITEM],
            "createLessonPlan" => &[PLAN_STARTED, PHASE, RESULT, PHASE],
            "streamLessonPlan" => &[PLAN_STARTED, PHASE, PENDING, RESULT],
            "sendTutorMessage" => &[DELTA, DONE, DELTA],
            "streamEvents" => &[ENVELOPE, DONE, ENVELOPE],
            other => panic!("the client has no method for stream {other}"),
        };
        let server = FakeServer::start(move |_, _| Reply::sse(&[], script, false)).await;
        let c = client(&server);
        let seen = match route.operation_id {
            "generateVocabulary" => tags(c.generate_vocabulary(&vocab()).await.unwrap()).await,
            "createLessonPlan" => {
                let body = serde_json::from_str(r#"{"context":"a night market","source_lang":"en","target_lang":"zh","level":2}"#).unwrap();
                tags::<CreateLessonPlanEvent>(c.create_lesson_plan(&body).await.unwrap()).await
            }
            "streamLessonPlan" => tags::<StreamLessonPlanEvent>(c.stream_lesson_plan("p1").await.unwrap()).await,
            "streamEvents" => tags::<StreamEventsEvent>(c.stream_events(&StreamEventsParams::default()).await.unwrap()).await,
            _ => {
                let body = serde_json::from_str(r#"{"message":"你好","source_lang":"en","target_lang":"zh"}"#).unwrap();
                tags::<SendTutorMessageEvent>(c.send_tutor_message(&body).await.unwrap()).await
            }
        };
        let want: &[&str] = match route.operation_id {
            "generateVocabulary" => &["started", "item"],
            "createLessonPlan" => &["started", "phase", "result"],
            "streamLessonPlan" => &["started", "phase", "pending"],
            "streamEvents" => &["event"],
            _ => &["delta"],
        };
        assert_eq!(seen, want, "{}", route.operation_id);
    }
}

/// 29.9.26p D4: every `EventStream` is `Unpin` and `Send`, and the client
/// and its error are `Send + Sync`, so a caller can move them into
/// `tokio::spawn`. A compile-time check: the test fails to build otherwise.
#[test]
fn the_stream_and_the_client_cross_threads() {
    fn send_unpin<T: Send + Unpin>() {}
    fn send_sync<T: Send + Sync>() {}
    send_unpin::<EventStream<GenerateVocabularyEvent>>();
    send_unpin::<EventStream<CreateLessonPlanEvent>>();
    send_unpin::<EventStream<StreamLessonPlanEvent>>();
    send_unpin::<EventStream<SendTutorMessageEvent>>();
    send_unpin::<EventStream<StreamEventsEvent>>();
    send_unpin::<crate::events::EventFeed>();
    send_unpin::<crate::events::EventTail>();
    send_sync::<Client>();
    send_sync::<Error>();
}

/// 29.9.26p AC16: an `error` event is `Err(ApiError)` with status 200, its
/// code, message and `plan_id` and the served version; then the stream
/// ends. No `Error` variant of the event enum is ever yielded.
#[tokio::test]
async fn an_error_event_raises_api_error_with_plan_id() {
    let error = "event: error\ndata: {\"code\":\"generation_failed\",\"message\":\"The model gave up.\",\"plan_id\":\"p1\"}\n\n";
    let mut events = vocab_stream(body(vec![(0, STARTED), (0, error), (0, ITEM)]), 1000);
    assert!(matches!(events.next().await, Some(Ok(GenerateVocabularyEvent::Started(_)))));
    match events.next().await {
        Some(Err(Error::Api(e))) => {
            assert_eq!((e.status, e.code.as_str(), e.message.as_str()), (200, "generation_failed", "The model gave up."));
            assert_eq!((e.plan_id.as_deref(), e.served_version.as_deref()), (Some("p1"), Some("2026-09-knowing-tenpounder")));
        }
        other => panic!("expected an ApiError, got {other:?}"),
    }
    assert!(events.next().await.is_none());
}

/// 29.9.26p AC17: a non-SSE `200` is `MalformedResponse` from the call, EOF
/// before a terminal is `StreamEndedEarly`, and bytes after a terminal are
/// never read.
#[tokio::test]
async fn the_stream_ends_per_c2_d6() {
    let server = FakeServer::start(|_, _| Reply::json(200, "{}")).await;
    let err = client(&server).generate_vocabulary(&vocab()).await.unwrap_err();
    assert!(matches!(err, Error::Transport(e) if e.kind == TransportKind::MalformedResponse));

    let mut early = vocab_stream(body(vec![(0, STARTED), (0, ITEM)]), 1000);
    assert!(early.next().await.unwrap().is_ok());
    assert!(early.next().await.unwrap().is_ok());
    assert_eq!(kind(early.next().await), Some(TransportKind::StreamEndedEarly));

    let read_after = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&read_after);
    let terminal = stream::iter([Ok(Bytes::from_static(DONE.as_bytes()))]);
    let after = stream::poll_fn(move |_| {
        flag.store(true, Ordering::SeqCst);
        Poll::Ready(Some(Ok(Bytes::from_static(ITEM.as_bytes()))))
    });
    let mut done = vocab_stream(Box::pin(terminal.chain(after)), 1000);
    assert!(done.next().await.is_none());
    assert!(done.next().await.is_none());
    assert!(!read_after.load(Ordering::SeqCst), "a byte after the terminal was read");
}
