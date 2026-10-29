use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let dialogue_id = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71";

    // lingara:begin getDialogue
    // The draft and the active revision, inline; either may be null.
    let dialogue = client.dialogues().get_dialogue(dialogue_id).await?;
    if let Some(draft) = &dialogue.draft {
        println!("draft {}", draft.draft_version);
    }
    if let Some(active) = &dialogue.active {
        println!("active revision {} since {}", active.revision, active.activated_at);
    }
    // lingara:end
    Ok(())
}
