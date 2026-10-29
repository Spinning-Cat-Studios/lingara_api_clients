use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin createDialogue
    use lingara::models::{CreateDialogueRequest, DialogueDefinition};

    // The definition is a dialogue.v1 document; the server validates it on
    // every save. Warnings are advice, never a refusal.
    let definition: DialogueDefinition = serde_json::from_value(serde_json::json!({
        "schema": "dialogue.v1", "id": "night_market_keepers", "start": "greet",
        "npcs": [{ "id": "old_wei", "name": "Old Wei" }],
        "checkpoints": [{ "id": "greet", "goal": "Greet the stranger", "may_disclose": [],
            "edges": [{ "id": "thanked", "to": "success", "when": "the player says thank you" }] }],
        "facts": [],
    }))?;
    let request = CreateDialogueRequest { definition, name: Some("Night market".into()) };
    let created = client.dialogues().create_dialogue(&request).await?;
    println!("{} draft {:?}", created.id, created.draft.as_ref().map(|d| d.draft_version));
    for w in &created.warnings {
        println!("warning {:?} at {}", w.code, w.path);
    }
    // lingara:end
    Ok(())
}
