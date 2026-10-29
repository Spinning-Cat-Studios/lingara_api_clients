use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let dialogue_id = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71";

    // lingara:begin activateDialogue
    use lingara::models::ActivateDialogueRequest;

    // Promote the draft you read: a stale or missing draft is 409 dialogue_conflict.
    let dialogues = client.dialogues();
    let current = dialogues.get_dialogue(dialogue_id).await?;
    if let Some(draft) = &current.draft {
        let request = ActivateDialogueRequest { draft_version: draft.draft_version };
        let activated = dialogues.activate_dialogue(dialogue_id, &request).await?;
        println!("active revision {:?}", activated.active.as_ref().map(|a| a.revision));
    }
    // lingara:end
    Ok(())
}
