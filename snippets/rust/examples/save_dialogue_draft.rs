use lingara::Client;
use lingara::models::DialogueDefinition;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let dialogue_id = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71";
    let definition: DialogueDefinition = serde_json::from_value(serde_json::json!({
        "schema": "dialogue.v1", "id": "night_market_keepers", "start": "greet", "npcs": [], "checkpoints": [], "facts": [],
    }))?;
    let draft_version = 4;

    // lingara:begin saveDialogueDraft
    use lingara::Error;
    use lingara::models::SaveDialogueDraftRequest;

    let dialogues = client.dialogues();
    let request = SaveDialogueDraftRequest { definition, draft_version, name: None };
    match dialogues.save_dialogue_draft(dialogue_id, &request).await {
        Ok(saved) => println!("saved; the next save sends {:?}", saved.draft.as_ref().map(|d| d.draft_version)),
        Err(Error::Api(e)) if e.code == "dialogue_conflict" => {
            // The draft moved since you read it, perhaps by your own retried
            // save. It is never retried for you: re-read, compare, and
            // decide. With no draft at all, send draft_version 0 to open one.
            let current = dialogues.get_dialogue(dialogue_id).await?;
            println!("changed elsewhere; the draft is now at {}", current.draft.as_ref().map_or(0, |d| d.draft_version));
        }
        Err(e) => return Err(e.into()),
    }
    // lingara:end
    Ok(())
}
