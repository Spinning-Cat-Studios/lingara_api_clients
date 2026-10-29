use lingara::Client;
use lingara::models::CanvasDefinition;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let canvas_id = "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b";
    let definition: CanvasDefinition =
        serde_json::from_value(serde_json::json!({ "schema": 2, "nodes": [{ "id": "n1", "kind": "client", "position": [0.0, 0.0] }] }))?;
    let draft_version = 4;

    // lingara:begin saveCanvasDraft
    use lingara::Error;
    use lingara::models::SaveCanvasDraftRequest;

    let canvases = client.canvases();
    let request = SaveCanvasDraftRequest { definition, draft_version, name: None };
    match canvases.save_canvas_draft(canvas_id, &request).await {
        Ok(saved) => println!("saved; the next save sends {:?}", saved.draft.as_ref().map(|d| d.draft_version)),
        Err(Error::Api(e)) if e.code == "canvas_conflict" => {
            // The draft moved since you read it, perhaps by your own retried
            // save. It is never retried for you: re-read, compare the draft
            // with the one you sent, and decide which to keep.
            let current = canvases.get_canvas(canvas_id).await?;
            if let Some(draft) = &current.draft {
                let same = serde_json::to_value(&draft.definition)? == serde_json::to_value(&request.definition)?;
                println!("{} at {}", if same { "already saved" } else { "changed elsewhere" }, draft.draft_version);
                // To keep yours, save again with draft.draft_version.
            }
        }
        Err(e) => return Err(e.into()),
    }
    // lingara:end
    Ok(())
}
