use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let dialogue_id = "4c8e2f1a-9b3d-4e7f-8a6c-2d1e0f9b8a71";
    let mut cached: Option<(String, String)> = None;

    // lingara:begin exportDialogue
    use lingara::dialogues::{ExportDialogueOptions, ExportedDialogue};

    // Cache by ETag: the body is canonical bytes, so store it exactly as
    // received. A 304 still spends one unit of the rate budget.
    let if_none_match = cached.as_ref().map(|(etag, _)| etag.clone());
    let options = ExportDialogueOptions { if_none_match, ..ExportDialogueOptions::default() };
    match client.dialogues().export_dialogue(dialogue_id, &options).await?.into_inner() {
        ExportedDialogue::Body { revision, etag, ir_json } => {
            println!("revision {revision} is new");
            cached = Some((etag, ir_json));
        }
        ExportedDialogue::NotModified { revision, .. } => println!("revision {revision} is unchanged"),
    }
    // lingara:end
    drop(cached);
    Ok(())
}
