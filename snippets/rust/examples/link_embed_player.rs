// lingara:begin linkEmbedPlayer
use std::sync::Arc;

use lingara::{AccessToken, BoxFuture, BuildError, Client, ClientBuilder, Error, TokenSource};

// The link is called with the player's own delegated lgr_at_ token: the one
// your server received when the player's Lingara account granted your app
// access (the authorization-code flow, which this library does not run yet).
// A client built from your client credentials is refused
// 403 embed_link_needs_grant. This source hands back the same token every
// time, so a revoked grant surfaces as a 401 after one repeat.
struct DelegatedToken(AccessToken);

impl TokenSource for DelegatedToken {
    fn token(&self) -> BoxFuture<'_, Result<AccessToken, Error>> {
        Box::pin(async move { Ok(self.0.clone()) })
    }

    fn invalidate(&self, _token: &AccessToken) {}
}

fn delegated_client(access_token: String, builder: ClientBuilder) -> Result<Client, BuildError> {
    builder.token_source(Arc::new(DelegatedToken(AccessToken::new(access_token)))).build()
}

async fn link_embed_player(client: &Client) -> Result<(), Error> {
    let link = client.link_embed_player("player-1001").await?.into_inner();
    // `Existing` is a success too. Call again while kept["lesson_plans"] is
    // non-zero: a plan still being written moves on a later call.
    println!("player-1001 is {:?} as {}", link.status, link.subject);
    Ok(())
}
// lingara:end

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = delegated_client(std::env::var("LINGARA_PLAYER_ACCESS_TOKEN")?, Client::builder())?;
    link_embed_player(&client).await?;
    Ok(())
}
