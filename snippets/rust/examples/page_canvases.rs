use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin pageCanvases
    use lingara::canvas::CanvasFilters;
    use futures_util::StreamExt as _;

    // Every canvas the filters match: the helper follows next_cursor and
    // resends the filters on every page. It never sleeps or polls.
    let filters = CanvasFilters { scopes: vec!["events:read".into()], ..CanvasFilters::default() };
    let mut canvases = client.canvases().page_canvases(filters);
    while let Some(canvas) = canvases.next().await {
        let canvas = canvas?;
        println!("{} {} ({} objects made)", canvas.id, canvas.name, canvas.objects.len());
    }
    // lingara:end
    Ok(())
}
