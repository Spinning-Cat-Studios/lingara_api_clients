use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let saved_cursor = std::env::args().nth(1);

    // lingara:begin listEvents
    use lingara::events::{Event, EventStart, EventsOptions};

    // From the saved cursor, or from every event still kept on a first run.
    let options = EventsOptions { start: Some(EventStart::Oldest), cursor: saved_cursor, ..EventsOptions::default() };
    let mut feed = client.events(options);
    while let Some(event) = feed.next().await {
        match event? {
            Event::LessonPlanReady(ready) => println!("plan {} is ready", ready.data.plan_id),
            event => println!("{} ({})", event.event_type(), event.id()),
        }
    }
    // Caught up: save this and pass it as `cursor` next time.
    println!("resume from {}", feed.cursor().unwrap_or_default());
    // lingara:end
    Ok(())
}
