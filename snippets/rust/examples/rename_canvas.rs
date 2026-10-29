use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let canvas_id = "6b1f0c3e-2a4d-4e8f-9b7a-1c2d3e4f5a6b";

    // lingara:begin renameCanvas
    use lingara::models::RenameCanvasRequest;

    // A PATCH: only the name changes, and no draft_version is needed.
    let renamed = client.canvases().rename_canvas(canvas_id, &RenameCanvasRequest { name: "Lesson plan webhook".into() }).await?;
    println!("now called {}", renamed.name);
    // lingara:end
    Ok(())
}
