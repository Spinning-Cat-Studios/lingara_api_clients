use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let canvas_id = "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b";

    // lingara:begin getCanvas
    // The draft and the active revision, inline; either may be None.
    let canvas = client.canvases().get_canvas(canvas_id).await?;
    if let Some(draft) = &canvas.draft {
        println!("draft {} has {} nodes", draft.draft_version, draft.definition.nodes.len());
    }
    if let Some(active) = &canvas.active {
        println!("active revision {} made {:?}", active.revision, active.objects.keys().collect::<Vec<_>>());
    }
    // lingara:end
    Ok(())
}
