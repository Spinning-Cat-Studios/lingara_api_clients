use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin unlinkEmbedPlayer
    // On your ordinary client holding embed:mint. A 404 embed_link_not_found
    // after a retried call means the link had already ended.
    client.unlink_embed_player("player-1001").await?;
    println!("player-1001 is unlinked");
    // lingara:end
    Ok(())
}
