use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // No credentials: this operation needs no token.
    let client = Client::builder().build()?;

    // lingara:begin getApiVersion
    let version = client.get_api_version("2026-09-knowing-tenpounder").await?;
    println!("{} {:?} {}", version.id, version.state, version.summary.as_deref().unwrap_or(""));
    // lingara:end
    Ok(())
}
