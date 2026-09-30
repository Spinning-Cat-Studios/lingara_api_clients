use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // No credentials: this operation needs no token.
    let client = Client::builder().build()?;

    // lingara:begin listApiVersions
    let list = client.list_api_versions().await?;
    println!("current: {}", list.current.as_deref().unwrap_or("(none)"));
    for version in &list.versions {
        println!("{} {:?}", version.id, version.state);
    }
    // lingara:end
    Ok(())
}
