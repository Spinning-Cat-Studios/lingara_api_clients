use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin createCanvas
    use lingara::models::{CreateCanvasRequest, CanvasDefinition};

    // A client node wired to a webhook node. The canvas starts as a draft;
    // the answer does not echo the recipe.
    let definition: CanvasDefinition = serde_json::from_value(serde_json::json!({
        "schema": 2,
        "nodes": [
            { "id": "n1", "kind": "client", "position": [0.0, 0.0] },
            { "id": "n2", "kind": "webhook", "position": [1.0, 0.0] }
        ],
        "edges": [{ "from": "n1", "to": "n2" }]
    }))?;
    let request = CreateCanvasRequest {
        name: Some("Tell my server when a lesson plan is ready".into()),
        recipe: Some(lingara::models::CanvasRecipe::LessonPlanReady),
        definition,
    };
    let canvas = client.canvases().create_canvas(&request).await?;
    println!("created {} at draft_version {:?}", canvas.id, canvas.draft.as_ref().map(|d| d.draft_version));
    // lingara:end
    Ok(())
}
