use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // No credentials: this operation needs no token.
    let client = Client::builder().build()?;

    // lingara:begin getOpenApiDocument
    let document = client.get_open_api_document().await?;
    let keys: Vec<&String> = document.as_object().map(|o| o.keys().collect()).unwrap_or_default();
    println!("{:?} {keys:?}", document.served_version());
    // lingara:end
    Ok(())
}
