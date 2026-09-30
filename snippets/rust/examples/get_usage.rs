use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin getUsage
    let usage = client.get_usage().await?;
    for row in &usage.allowance {
        println!("{} {}: {} of {} left", row.feature, row.window, row.remaining, row.limit);
    }
    // lingara:end
    Ok(())
}
