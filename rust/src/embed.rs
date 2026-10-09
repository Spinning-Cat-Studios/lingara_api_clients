//! Embedding Lingara (ADR 1.10.26w): the mint, the player delete and the
//! NPC turn. The operations are as thin as `client.rs`'s; what is
//! hand-written here is what a generator cannot know, that a minted token is
//! a credential (D3).

use std::time::Duration;

use reqwest::Method;

use crate::client::{ApiResponse, Client};
use crate::error::{Error, TransportKind};
use crate::generated::streams::SEND_DIALOGUE_TURN;
use crate::models::{DialogueTurnRequest, EmbedToken, EmbedTokenRequest, SendDialogueTurnEvent};
use crate::pipeline::{Req, encode_segment};
use crate::stream::EventStream;
use crate::token::AccessToken;

/// A player's embed token, as [`Client::create_embed_token`] returns it.
/// Its `Debug` renders the token as `[REDACTED]` (CONTRACT.md K1);
/// `token.expose_secret()` is the one way to read it.
#[derive(Clone, Debug)]
pub struct MintedToken {
    /// The `lgr_et_` bearer token to hand to the player's device.
    pub token: AccessToken,
    /// When the token stops working, as the server's RFC 3339 string.
    /// Lingara never refreshes it: mint again.
    pub expires_at: String,
    /// The token's lifetime, counted from the answer. Prefer it on a device
    /// whose clock cannot be trusted.
    pub expires_in: Duration,
    /// The player's pairwise `lgr_sub_`, stable across mints. Store it
    /// beside the player: it is how an event names them.
    pub subject: String,
    /// The scopes granted: every handable scope the client holds when the
    /// request named none.
    pub scopes: Vec<String>,
    /// Whether the player has linked a Lingara account.
    pub account_linked: bool,
}

impl MintedToken {
    /// From the decoded answer, whose six fields serde has already required
    /// and typed. A token that is not an `lgr_et_` token is
    /// `MalformedResponse`, carrying none of the answer, so the value cannot
    /// leak through a cause.
    fn from_answer(answer: EmbedToken) -> Result<Self, Error> {
        if !answer.token.starts_with("lgr_et_") {
            return Err(TransportKind::MalformedResponse.into());
        }
        Ok(Self {
            token: AccessToken::new(answer.token),
            expires_at: answer.expires_at,
            expires_in: Duration::from_secs(answer.expires_in.into()),
            subject: answer.subject,
            scopes: answer.scopes,
            account_linked: answer.account_linked,
        })
    }
}

impl Client {
    /// Mints a token for one player (`POST /v1/embed/tokens`; scope
    /// `embed:mint`, and only for a metered client). Call it on your server,
    /// never on the player's device, and mint again when the token expires:
    /// Lingara never refreshes one. A `403` `insufficient_scope` or
    /// `embed_needs_metered` is the server's answer, raised as an `ApiError`.
    /// K4's `Retry-After` loop applies; two mints for one player are
    /// harmless.
    pub async fn create_embed_token(&self, body: &EmbedTokenRequest) -> Result<ApiResponse<MintedToken>, Error> {
        let url = self.url("/v1/embed/tokens");
        let req = Req { method: Method::POST, url: &url, body: Some(body), accept: "application/json", needs_token: true, headers: &[], retries: true };
        let res = self.json_req::<EmbedToken, _>(&req).await?;
        let served_version = res.served_version;
        Ok(ApiResponse { value: MintedToken::from_answer(res.value)?, served_version })
    }

    /// Deletes a player and revokes their tokens (`DELETE
    /// /v1/embed/players/{player_ref}`; scope `embed:mint`). `player_ref` is
    /// sent as one percent-encoded path segment. An unknown player is still
    /// a success, so the call is idempotent and K4's retries apply; it keeps
    /// working while embedding is switched off for your client. The answer
    /// has no body: the result carries only `served_version()`.
    pub async fn delete_embed_player(&self, player_ref: &str) -> Result<ApiResponse<()>, Error> {
        let url = self.url(&format!("/v1/embed/players/{}", encode_segment(player_ref)));
        let req = Req { method: Method::DELETE, url: &url, body: None::<&()>, accept: "application/json", needs_token: true, headers: &[], retries: true };
        self.empty_req(&req).await
    }

    /// Streams an NPC's reply to one line (`POST /v1/embed/dialogue/turns`;
    /// scope `embed:play`, from an embed token or a metered client's own
    /// token). It yields `Delta` and `Notice` events and ends on `done`.
    ///
    /// The window is yours to keep: at most 12 `history` entries, with
    /// `line` and each entry at most 500 characters, and no total cap. Send
    /// each NPC reply back into `history` cut to its first 500 characters.
    /// The library neither checks nor trims it.
    ///
    /// It is never retried: each attempt spends the player's NPC cells and
    /// your metered cells, so a `429` or `503` is raised at once as an
    /// `ApiError` with its `retry_after`, and you decide whether to send the
    /// turn again. Two refusals no retry helps: `403 embed_needs_metered`,
    /// and `422 safety_input_flagged`, which means say something else.
    pub async fn send_dialogue_turn(&self, body: &DialogueTurnRequest) -> Result<EventStream<SendDialogueTurnEvent>, Error> {
        let url = self.url(SEND_DIALOGUE_TURN.path);
        let req = Req { method: Method::POST, url: &url, body: Some(body), accept: "text/event-stream", needs_token: true, headers: &[], retries: false };
        self.open_stream(&SEND_DIALOGUE_TURN, &req).await
    }
}

#[cfg(test)]
#[path = "tests/embed_tests.rs"]
mod tests;
