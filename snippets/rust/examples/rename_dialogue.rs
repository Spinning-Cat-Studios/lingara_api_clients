use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let dialogue_id = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71";

    // lingara:begin renameDialogue
    use lingara::models::RenameDialogueRequest;

    let request = RenameDialogueRequest { name: "Night market keepers".into() };
    let renamed = client.dialogues().rename_dialogue(dialogue_id, &request).await?;
    println!("{}", renamed.name);
    // lingara:end
    Ok(())
}
