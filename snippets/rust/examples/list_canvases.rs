use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin listCanvases
    use lingara::canvas::{CanvasFilters, ListCanvasesParams};

    // One page, newest first; pass next_cursor back as cursor for the next
    // one, or let page_canvases walk them all.
    let filters = CanvasFilters { status: Some("live".into()), kinds: vec!["webhook".into()], limit: Some(20), ..CanvasFilters::default() };
    let page = client.canvases().list_canvases(&ListCanvasesParams { cursor: None, filters }).await?;
    for canvas in &page.items {
        println!("{} {} {:?}", canvas.name, canvas.recipe, canvas.active_revision);
    }
    if page.has_more {
        println!("more from {:?}", page.next_cursor);
    }
    // lingara:end
    Ok(())
}
