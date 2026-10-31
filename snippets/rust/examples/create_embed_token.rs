use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin createEmbedToken
    use lingara::models::{EmbedTokenRequest, EmbedTokenRequestScopesItem};

    // On your server, from a metered client holding embed:mint. The token
    // (lgr_et_…) lives 900 s and is never refreshed: mint again when the
    // player's device asks. Pass on the Lingara-Embed-Version header the
    // player kit sent, to be told when that build is out of date.
    let embed_version = "1.1.0";
    let request = EmbedTokenRequest {
        player_ref: "player-1001".parse()?,
        scopes: Some(vec![EmbedTokenRequestScopesItem::EmbedPlay, EmbedTokenRequestScopesItem::EventsRead]),
        origin: None,
        embed_version: Some(embed_version.parse()?),
    };
    let minted = client.create_embed_token(&request).await?;
    // Store the subject beside your player: it is how an event names them.
    println!("player-1001 is {}", minted.subject);
    // A notice is for you, the developer: the build is behind or deprecated.
    if let Some(notice) = &minted.update_available {
        println!("embed {embed_version} is out of date; latest {:?}, deprecated {}", notice.latest, notice.deprecated);
    }
    // Hand the token and its expiry to the player's device, and log neither.
    let handoff = (minted.token.expose_secret(), minted.expires_at.as_str(), minted.expires_in.as_secs());
    // lingara:end
    let _ = handoff;
    Ok(())
}
