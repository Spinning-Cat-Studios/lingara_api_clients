use std::num::NonZeroU8;

use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin sendEvent
    use lingara::events::{InboundEvent, SendEventOptions};
    use lingara::models::{PlanStatus, WorldContextChanged};

    let event = InboundEvent::WorldContextChanged(WorldContextChanged {
        scene: "A night market after rain".parse()?,
        npc: None,
        source_lang: "en".into(),
        target_lang: "zh".into(),
        level: NonZeroU8::new(2).ok_or("level")?,
        tags: Vec::new(),
        generate: Some(true),
    });
    // Your own key makes a resend after a crash safe; reuse it only for this event.
    let options = SendEventOptions { idempotency_key: Some("save-17/night-market".into()) };
    let accepted = client.send_event(&event, options).await?;
    println!("recorded {}", accepted.id);
    if let Some(reaction) = &accepted.reaction {
        // Only `generating` promises a lesson_plan.ready or .failed event.
        if reaction.plan_status == Some(PlanStatus::Generating) {
            println!("plan {} is on its way", reaction.plan_id.as_deref().unwrap_or_default());
        }
    }
    // lingara:end
    Ok(())
}
