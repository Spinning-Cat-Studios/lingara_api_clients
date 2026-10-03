use std::collections::HashMap;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    // What your server framework hands a handler: the raw body bytes, and the
    // request headers (axum's and hyper's `HeaderMap` also works as-is).
    let body: Vec<u8> = std::fs::read(std::env::args().nth(1).unwrap_or_else(|| "delivery.json".into()))?;
    let headers: HashMap<String, String> = ["webhook-id", "webhook-timestamp", "webhook-signature"]
        .into_iter()
        .filter_map(|name| Some((name.to_owned(), std::env::var(name.replace('-', "_").to_uppercase()).ok()?)))
        .collect();

    // lingara:begin verifyWebhook
    use lingara::events::{Event, Webhook};

    let webhook = Webhook::new(&std::env::var("LINGARA_WEBHOOK_SECRET")?)?;
    // Verify the raw bytes, before any JSON parsing; then answer 2xx fast.
    match webhook.verify(&body, &headers) {
        Ok(Event::LessonPlanReady(ready)) => println!("plan {} is ready ({})", ready.data.plan_id, ready.id),
        // A type newer than this crate: acknowledge it and log it.
        Ok(Event::Unknown(event)) => println!("new event type {} ({})", event.type_, event.id),
        Ok(event) => println!("{} ({})", event.event_type(), event.id()),
        // Answer 400: the delivery is not from Lingara, or was altered.
        Err(refused) => eprintln!("refused: {}", refused.reason()),
    }
    // lingara:end
    Ok(())
}
