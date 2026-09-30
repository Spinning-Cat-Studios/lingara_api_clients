use std::sync::Arc;
use std::time::Duration;

use futures_util::future::join_all;
use secrecy::SecretString;

use super::{AccessToken, ClientCredentials, ExchangeConfig, TokenAuth, TokenSource};
use crate::error::Error;
use crate::fake_server::{FakeClock, FakeServer, RecordingSleeper, Reply};
use crate::retry::RetryPolicy;

const START: u64 = 1_790_000_000;

fn grant(token: &str, expires_in: u64) -> Reply {
    Reply::json(200, &format!(r#"{{"access_token":"{token}","token_type":"Bearer","expires_in":{expires_in}}}"#))
}

fn source(server: &FakeServer, clock: Arc<FakeClock>) -> ClientCredentials {
    let policy = RetryPolicy { max_attempts: 3, retry_after_cap: Duration::from_secs(60), clock, sleeper: Arc::new(RecordingSleeper::default()) };
    let config = ExchangeConfig {
        http: reqwest::Client::new(),
        token_url: format!("{}/oauth/token", server.url),
        user_agent: "lingara-rust/0.0.0 (test)".into(),
        auth: TokenAuth::Basic,
        scopes: Vec::new(),
        policy,
        request_timeout: Duration::from_secs(5),
    };
    ClientCredentials::new("lgr_cid_test".into(), SecretString::from("lgr_cs_test"), config)
}

fn secret(result: &Result<AccessToken, Error>) -> &str {
    result.as_ref().expect("a token").expose_secret()
}

/// 29.9.26p AC3: eight concurrent `token()` calls cause one exchange; when
/// it fails, all eight get the same error and nothing is cached.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn single_flight_shares_one_exchange_and_caches_no_failure() {
    let ok = FakeServer::start(|_, _| grant("lgr_at_one", 3600).after(Duration::from_millis(200))).await;
    let tokens = source(&ok, FakeClock::at(START));
    let results = join_all((0..8).map(|_| tokens.token())).await;
    assert!(results.iter().all(|r| secret(r) == "lgr_at_one"));
    assert_eq!(ok.requests().len(), 1);

    let failing = FakeServer::start(|_, n| match n {
        0 => Reply::json(500, r#"{"error":"server_error","error_description":"try later"}"#).after(Duration::from_millis(200)),
        _ => grant("lgr_at_two", 3600),
    })
    .await;
    let tokens = source(&failing, FakeClock::at(START));
    let results = join_all((0..8).map(|_| tokens.token())).await;
    assert_eq!(failing.requests().len(), 1);
    let rendered: Vec<String> = results.iter().map(|r| r.as_ref().expect_err("the flight failed").to_string()).collect();
    assert!(results.iter().all(|r| matches!(r, Err(Error::OAuth(e)) if e.error == "server_error")));
    assert!(rendered.iter().all(|r| r == &rendered[0]), "{rendered:?}");
    // Nothing was cached: the next call starts a new exchange.
    assert_eq!(secret(&tokens.token().await), "lgr_at_two");
    assert_eq!(failing.requests().len(), 2);
}

/// 29.9.26p AC4: with `expires_in: 3600` a token is reused at 3539 s and
/// replaced at 3541 s after send; with `expires_in: 40` it is stale at 20 s.
#[tokio::test]
async fn refreshes_at_min_of_sixty_seconds_and_half_the_lifetime() {
    let server = FakeServer::start(|_, n| grant(&format!("lgr_at_{n}"), 3600)).await;
    let clock = FakeClock::at(START);
    let tokens = source(&server, Arc::clone(&clock));
    assert_eq!(secret(&tokens.token().await), "lgr_at_0");
    clock.advance(Duration::from_secs(3539));
    assert_eq!(secret(&tokens.token().await), "lgr_at_0");
    clock.advance(Duration::from_secs(2));
    assert_eq!(secret(&tokens.token().await), "lgr_at_1");

    let short = FakeServer::start(|_, n| grant(&format!("lgr_at_short_{n}"), 40)).await;
    let clock = FakeClock::at(START);
    let tokens = source(&short, Arc::clone(&clock));
    assert_eq!(secret(&tokens.token().await), "lgr_at_short_0");
    clock.advance(Duration::from_secs(19));
    assert_eq!(secret(&tokens.token().await), "lgr_at_short_0");
    clock.advance(Duration::from_secs(1));
    assert_eq!(secret(&tokens.token().await), "lgr_at_short_1");
}

/// 29.9.26p AC5: `invalidate` of an older token leaves a newer cached token
/// in place.
#[tokio::test]
async fn invalidate_is_compare_and_clear() {
    let server = FakeServer::start(|_, n| grant(&format!("lgr_at_{n}"), 3600)).await;
    let clock = FakeClock::at(START);
    let tokens = source(&server, Arc::clone(&clock));
    let older = tokens.token().await.unwrap();
    clock.advance(Duration::from_secs(3600));
    let newer = tokens.token().await.unwrap();
    assert_eq!(newer.expose_secret(), "lgr_at_1");

    tokens.invalidate(&older);
    assert_eq!(secret(&tokens.token().await), "lgr_at_1");
    assert_eq!(server.requests().len(), 2);

    tokens.invalidate(&newer);
    assert_eq!(secret(&tokens.token().await), "lgr_at_2");
}

/// 29.9.26p AC6: dropping the waiter that started an exchange leaves the
/// flight running, and its token is cached for the next caller.
#[tokio::test]
async fn a_dropped_waiter_leaves_the_flight_running() {
    let server = FakeServer::start(|_, _| grant("lgr_at_flight", 3600).after(Duration::from_millis(200))).await;
    let tokens = source(&server, FakeClock::at(START));
    let abandoned = tokio::time::timeout(Duration::from_millis(50), tokens.token()).await;
    assert!(abandoned.is_err(), "the first waiter was dropped mid-flight");

    tokio::time::sleep(Duration::from_millis(400)).await;
    assert_eq!(secret(&tokens.token().await), "lgr_at_flight");
    assert_eq!(server.requests().len(), 1);
}
