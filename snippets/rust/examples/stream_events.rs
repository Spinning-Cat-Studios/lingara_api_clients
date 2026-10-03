use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let saved_cursor = std::env::args().nth(1);

    // lingara:begin streamEvents
    use lingara::events::EventsOptions;

    // Live events from the saved cursor (a feed cursor works too). The tail
    // reconnects on its own; it raises only after eight failed opens in a row.
    let mut tail = client.tail_events(EventsOptions { cursor: saved_cursor, ..EventsOptions::default() });
    while let Some(event) = tail.next().await {
        let event = event?;
        println!("{} ({})", event.event_type(), event.id());
        println!("resume from {}", tail.cursor().unwrap_or_default());
    }
    // lingara:end
    Ok(())
}
