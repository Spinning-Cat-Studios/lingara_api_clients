use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin errors
    use lingara::Error;

    let result = client.get_usage().await;
    if let Some(wait) = result.as_ref().err().and_then(Error::retry_after) {
        eprintln!("the API asks you to retry after {wait:?}");
    }
    match result {
        Ok(usage) => println!("{} allowance rows", usage.allowance.len()),
        // A refusal from the API: status, code (stable) and message (localised).
        Err(Error::Api(e)) => eprintln!("{} {} {}", e.status, e.code, e.message),
        // The token endpoint refused the credentials or the scopes.
        Err(Error::OAuth(e)) => eprintln!("{} {} {}", e.status, e.error, e.description.unwrap_or_default()),
        Err(Error::Maintenance(_)) => eprintln!("the API is under maintenance"),
        // No usable answer: connect, tls, reset, timeout, and so on.
        Err(Error::Transport(e)) => eprintln!("transport: {}", e.kind),
        Err(other) => return Err(other.into()),
    }
    // lingara:end
    Ok(())
}
