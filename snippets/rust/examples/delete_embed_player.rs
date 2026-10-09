use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin deleteEmbedPlayer
    // Deletes the player and revokes their tokens. An unknown player is still a success.
    client.delete_embed_player("player-1001").await?;
    println!("player-1001 is gone");
    // lingara:end
    Ok(())
}
