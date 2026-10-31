use std::sync::Arc;
use std::time::Duration;

use crate::error::{Error, TransportKind};
use crate::fake_server::{FakeServer, Reply};
use crate::models::EmbedTokenRequest;
use crate::{AccessToken, BoxFuture, Client, TokenSource};

const TOKEN: &str = "lgr_et_unit00000000000000000000000000000000000000";
const MINTED: &str = r#"{"token":"lgr_et_unit00000000000000000000000000000000000000","expires_at":"2026-10-01T09:27:44Z","expires_in":900,"subject":"lgr_sub_unit","scopes":["embed:play"],"account_linked":false}"#;
const NO_SUBJECT: &str = r#"{"token":"lgr_et_unit00000000000000000000000000000000000000","expires_at":"2026-10-01T09:27:44Z","expires_in":900,"scopes":["embed:play"],"account_linked":false}"#;
const NOT_EMBED: &str = r#"{"token":"lgr_at_unit","expires_at":"2026-10-01T09:27:44Z","expires_in":900,"subject":"lgr_sub_unit","scopes":[],"account_linked":false}"#;

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

fn request() -> EmbedTokenRequest {
    serde_json::from_str(r#"{"player_ref":"player-1001"}"#).unwrap()
}

/// 1.10.26w AC14: a `MintedToken` redacts under `{:?}` and `{:#?}`, and
/// `token.expose_secret()` returns the value. An answer missing `subject`,
/// or holding a token that is not `lgr_et_`, is refused as
/// `malformed_response`, and the error renders none of the answer.
#[tokio::test]
async fn a_minted_token_renders_redacted() {
    let server = FakeServer::start(|_, n| Reply::json(200, [MINTED, NO_SUBJECT, NOT_EMBED][n])).await;
    let c = client(&server);
    let minted = c.create_embed_token(&request()).await.unwrap();
    for rendering in [format!("{minted:?}"), format!("{minted:#?}"), format!("{:?}", *minted), format!("{:#?}", *minted)] {
        assert!(!rendering.contains(TOKEN) && rendering.contains("[REDACTED]"), "{rendering}");
    }
    assert_eq!(minted.token.expose_secret(), TOKEN);
    assert_eq!((minted.expires_at.as_str(), minted.expires_in), ("2026-10-01T09:27:44Z", Duration::from_secs(900)));
    assert_eq!((minted.subject.as_str(), minted.scopes.as_slice(), minted.account_linked), ("lgr_sub_unit", &["embed:play".to_owned()][..], false));

    for _ in 0..2 {
        let err = c.create_embed_token(&request()).await.unwrap_err();
        assert!(matches!(&err, Error::Transport(e) if e.kind == TransportKind::MalformedResponse), "{err:?}");
        assert!(std::error::Error::source(&err).is_none());
        for rendering in [err.to_string(), format!("{err:?}"), format!("{err:#?}")] {
            assert!(!rendering.contains("lgr_et_") && !rendering.contains("lgr_at_unit"), "{rendering}");
        }
    }
}

/// A mint answer carrying `notice` as its `update_available`, raw.
fn minted_with(notice: &str) -> String {
    format!("{},\"update_available\":{notice}}}", MINTED.trim_end_matches('}'))
}

/// 31.10.26g AC1: a well-formed notice is copied field for field, `null`s
/// kept, and a message of exactly 280 astral code points is kept; none,
/// absent or `null`, is `None`; a malformed notice is `None` and the token is
/// still minted; a malformed token field is still `MalformedResponse`.
#[tokio::test]
async fn update_available_is_copied_or_dropped() {
    let smileys = "\u{1F600}".repeat(280);
    let well_formed = [
        (r#"{"latest":null,"deprecated":true,"message":"Use 1.2.0"}"#.to_owned(), None, true, Some("Use 1.2.0".to_owned())),
        (format!(r#"{{"latest":"1.2.0","deprecated":false,"message":"{smileys}"}}"#), Some("1.2.0".to_owned()), false, Some(smileys.clone())),
    ];
    let malformed = [
        "5".to_owned(),
        r#"{"latest":"1.2.0","message":null}"#.to_owned(),
        r#"{"latest":7,"deprecated":true,"message":null}"#.to_owned(),
        format!(r#"{{"latest":null,"deprecated":true,"message":"{smileys}x"}}"#),
    ];
    let mut answers: Vec<String> = well_formed.iter().map(|(n, ..)| minted_with(n)).collect();
    answers.extend([MINTED.to_owned(), minted_with("null")]);
    answers.extend(malformed.iter().map(|n| minted_with(n)));
    answers.push(r#"{"token":"lgr_et_x","expires_at":"x","expires_in":"soon","subject":"s","scopes":[],"account_linked":false,"update_available":null}"#.to_owned());
    let served = answers.clone();
    let server = FakeServer::start(move |_, n| Reply::json(200, &served[n])).await;
    let c = client(&server);

    for (_, latest, deprecated, message) in &well_formed {
        let notice = c.create_embed_token(&request()).await.unwrap().value.update_available.expect("kept");
        assert_eq!((&notice.latest, notice.deprecated, notice.message.as_deref().map(String::as_str)), (latest, *deprecated, message.as_deref()));
    }
    for _ in 0..2 + malformed.len() {
        let minted = c.create_embed_token(&request()).await.unwrap();
        assert!(minted.update_available.is_none(), "{minted:?}");
        assert_eq!(minted.token.expose_secret(), TOKEN);
    }
    let err = c.create_embed_token(&request()).await.unwrap_err();
    assert!(matches!(&err, Error::Transport(e) if e.kind == TransportKind::MalformedResponse), "{err:?}");
}

/// 1.10.26w D4, D5: the delete is a `DELETE` with no body and no
/// `content-type`, its `player_ref` one encoded segment. An empty `204` and
/// a `200` with a body are both success, the body unread, and the echo is
/// exposed.
#[tokio::test]
async fn a_player_delete_sends_no_body_and_reads_none() {
    let server = FakeServer::start(|_, n| match n {
        0 => Reply::with(204, &[("lingara-version", "2026-10-golden-remora")], ""),
        _ => Reply::json(200, "not json at all"),
    })
    .await;
    let c = client(&server);
    let deleted = c.delete_embed_player("guild/42 (é)*").await.unwrap();
    assert_eq!(deleted.served_version(), Some("2026-10-golden-remora"));
    c.delete_embed_player("p-1001").await.unwrap();
    let sent = &server.requests()[0];
    assert_eq!((sent.method.as_str(), sent.path.as_str()), ("DELETE", "/v1/embed/players/guild%2F42%20%28%C3%A9%29%2A"));
    assert_eq!((sent.header("content-type"), sent.body.as_str()), (None, ""));
}
