use std::sync::Arc;
use std::time::Duration;

use tokio::sync::Notify;

use super::backoff;
use crate::events::{Event, EventsOptions};
use crate::fake_server::{FakeServer, RecordingSleeper, Reply};
use crate::{AccessToken, BoxFuture, Client, Error, Sleeper, TokenSource};

const EVENT_C1: &str = "id: c1\nevent: event\ndata: {\"id\":\"lgr_evt_1\",\"type\":\"lesson_plan.failed\",\"created_at\":\"2026-10-01T09:13:02Z\",\"api_version\":\"2026-09-equipped-boxfish\",\"subject\":\"lgr_sub_1\",\"data\":{\"plan_id\":\"p1\",\"reason\":\"generation_failed\"}}\n\n";

/// A token source that never exchanges.
struct Fixed;

impl TokenSource for Fixed {
    fn token(&self) -> BoxFuture<'_, Result<AccessToken, Error>> {
        Box::pin(async { Ok(AccessToken::new("lgr_at_fixed")) })
    }
    fn invalidate(&self, _: &AccessToken) {}
}

/// A reconnect sleep that never ends, and says when it has begun.
struct Stuck(Arc<Notify>);

impl Sleeper for Stuck {
    fn sleep(&self, _: Duration) -> BoxFuture<'static, ()> {
        self.0.notify_one();
        Box::pin(std::future::pending())
    }
}

fn client(server: &FakeServer, sleeper: Arc<dyn Sleeper>) -> Client {
    Client::builder().base_url(&server.url).token_source(Arc::new(Fixed)).sleeper(sleeper).build().unwrap()
}

/// 30.9.26aa AC32: the connection ends after one event, so the tail sleeps
/// before it reopens; dropping the tail during that sleep ends it, and the
/// server never sees a second request.
#[tokio::test]
async fn a_dropped_tail_makes_no_further_request() {
    let server = FakeServer::start(|_, _| Reply::sse(&[], &[EVENT_C1], false)).await;
    let asleep = Arc::new(Notify::new());
    let mut tail = client(&server, Arc::new(Stuck(Arc::clone(&asleep)))).tail_events(EventsOptions::default());
    assert!(matches!(tail.next().await, Some(Ok(Event::LessonPlanFailed(_)))));
    assert_eq!(tail.cursor(), Some("c1"));
    tokio::select! {
        item = tail.next() => panic!("the tail yielded {item:?} instead of sleeping"),
        () = asleep.notified() => {}
    }
    drop(tail);
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert_eq!(server.requests().len(), 1);
}

/// CONTRACT.md K5a: a `done` is not yielded; its `id:` becomes the cursor
/// and the reopen carries it as `Last-Event-ID` at once, with no sleep. The
/// first open, given no cursor, sends none.
#[tokio::test]
async fn a_done_moves_the_cursor_and_reopens_at_once() {
    let server = FakeServer::start(|_, n| match n {
        0 => Reply::sse(&[], &["id: h1\nevent: done\ndata: {}\n\n"], false),
        _ => Reply::sse(&[], &[EVENT_C1], true),
    })
    .await;
    let sleeper = Arc::new(RecordingSleeper::default());
    let mut tail = client(&server, Arc::clone(&sleeper) as Arc<dyn Sleeper>).tail_events(EventsOptions::default());
    assert!(matches!(tail.next().await, Some(Ok(Event::LessonPlanFailed(_)))));
    assert_eq!(tail.cursor(), Some("c1"));
    let requests = server.requests();
    assert_eq!(requests[0].header("last-event-id"), None);
    assert_eq!(requests[1].header("last-event-id"), Some("h1"));
    assert!(sleeper.slept().is_empty());
}

/// CONTRACT.md K5a: 1, 2, 4, 8, 16, then 30 s for every later step.
#[test]
fn the_backoff_doubles_to_thirty_seconds() {
    let steps: Vec<u64> = (1..=9).map(|n| backoff(n).as_secs()).collect();
    assert_eq!(steps, [1, 2, 4, 8, 16, 30, 30, 30, 30]);
}
