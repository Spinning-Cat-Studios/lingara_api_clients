use std::sync::Arc;
use std::time::{Duration, SystemTime};

use crate::Client;
use crate::error::Error;
use crate::fake_server::{FakeClock, FakeServer, RecordingSleeper, Reply};

const START: u64 = 1_790_000_000;
const VERSIONS: &str = r#"{"current":"2026-09-knowing-tenpounder","development":null,"versions":[]}"#;

/// A credential-free client on `server`, with a virtual clock and a
/// recording sleeper.
fn client(server: &FakeServer, sleeper: &Arc<RecordingSleeper>) -> Client {
    Client::builder()
        .base_url(&server.url)
        .clock(FakeClock::at(START))
        .sleeper(Arc::clone(sleeper) as Arc<dyn crate::Sleeper>)
        .build()
        .unwrap()
}

fn rate_limited(retry_after: Option<&str>) -> Reply {
    let body = r#"{"code":"rate_limited","error":"Slow down."}"#;
    match retry_after {
        Some(value) => Reply::with(429, &[("content-type", "application/json"), ("retry-after", value)], body),
        None => Reply::json(429, body),
    }
}

fn secs(values: &[u64]) -> Vec<Duration> {
    values.iter().copied().map(Duration::from_secs).collect()
}

/// 29.9.26p AC7: a `Retry-After` above `retry_after_cap` returns at once with
/// `retry_after` set; a missing one returns at once; an HTTP-date is read
/// against the `Clock`; three 429s return after two sleeps.
#[tokio::test]
async fn retry_after_cap_missing_header_date_and_exhaustion() {
    // Above the 60 s cap: raised at once, carrying the wait.
    let server = FakeServer::start(|_, _| rate_limited(Some("120"))).await;
    let sleeper = Arc::new(RecordingSleeper::default());
    let err = client(&server, &sleeper).list_api_versions().await.unwrap_err();
    assert!(matches!(&err, Error::Api(e) if e.status == 429 && e.code == "rate_limited"));
    assert_eq!(err.retry_after(), Some(Duration::from_secs(120)));
    assert_eq!((server.requests().len(), sleeper.slept()), (1, vec![]));

    // No Retry-After: raised at once.
    let server = FakeServer::start(|_, _| rate_limited(None)).await;
    let sleeper = Arc::new(RecordingSleeper::default());
    let err = client(&server, &sleeper).list_api_versions().await.unwrap_err();
    assert_eq!(err.retry_after(), None);
    assert_eq!((server.requests().len(), sleeper.slept()), (1, vec![]));

    // An HTTP-date 30 s after the virtual clock: slept 30 s, then retried.
    let date = httpdate::fmt_http_date(SystemTime::UNIX_EPOCH + Duration::from_secs(START + 30));
    let server = FakeServer::start(move |_, n| if n == 0 { rate_limited(Some(&date)) } else { Reply::json(200, VERSIONS) }).await;
    let sleeper = Arc::new(RecordingSleeper::default());
    let versions = client(&server, &sleeper).list_api_versions().await.unwrap();
    assert_eq!(versions.current.as_deref(), Some("2026-09-knowing-tenpounder"));
    assert_eq!(sleeper.slept(), secs(&[30]));

    // Three 429s under the default three attempts: two sleeps, then raised.
    let server = FakeServer::start(|_, _| rate_limited(Some("1"))).await;
    let sleeper = Arc::new(RecordingSleeper::default());
    let err = client(&server, &sleeper).list_api_versions().await.unwrap_err();
    assert!(matches!(&err, Error::Api(e) if e.status == 429));
    assert_eq!((server.requests().len(), sleeper.slept()), (3, secs(&[1, 1])));
}
