use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let canvas_id = "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b";

    // lingara:begin activateCanvas
    use lingara::models::{ActivateCanvasRequest, CanvasActivationOutcome, CanvasObjectRef};

    // After your apply: the draft_version you applied, whether every op
    // landed, and the object each node handle made. Replaying an activate
    // that already landed answers 409 canvas_conflict and writes nothing.
    let objects = [("n1".to_owned(), CanvasObjectRef { id: "0b9e4c2d-7a1f-4e3b-9d8c-5f6a7b8c9d0e".into() })].into();
    let request = ActivateCanvasRequest { draft_version: 4, outcome: CanvasActivationOutcome::Complete, objects };
    let canvas = client.canvases().activate_canvas(canvas_id, &request).await?;
    println!("active revision {:?}", canvas.active.as_ref().map(|a| a.revision));
    // lingara:end
    Ok(())
}
