use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    // Your dialogue.v2 Procedural document, as JSON.
    let document = serde_json::json!({"schema": "dialogue.v2", "kind": "procedural", "id": "the_ferryman"});

    // lingara:begin planDialogue
    use lingara::models::{DialoguePlanRequest, DialoguePlanResponse, PlanReason};

    // The first plan of a Procedural dialogue. Inside the embed kits the kit
    // calls this for you. Never retried: each call spends one embed:play
    // unit, a keep too.
    let request = DialoguePlanRequest {
        document: serde_json::from_value(document)?,
        npc: "ma_ren".parse()?,
        reason: PlanReason::Start,
        current: None,
        frontier: None,
        id_prefix: "p1".into(),
        open_topics: Vec::new(),
        goals_met: Vec::new(),
        visited: Vec::new(),
        turns: 0,
        beats: 0,
        history: Vec::new(),
    };
    match client.plan_dialogue(&request).await?.into_inner() {
        DialoguePlanResponse::Planned { fragment } => {
            for beat in &fragment.beats {
                println!("{} {} {}", beat.id, beat.atom, beat.angle.as_deref().unwrap_or(""));
            }
        }
        // A keep is a result: your frontier stands (on a first plan, walk the seed).
        DialoguePlanResponse::Kept { reason } => println!("kept: {reason}"),
    }
    // lingara:end
    Ok(())
}
