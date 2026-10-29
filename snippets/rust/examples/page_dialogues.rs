use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin pageDialogues
    use futures_util::StreamExt as _;
    use lingara::dialogues::DialogueFilters;

    // Every dialogue the filters match: the helper follows next_cursor and
    // resends the filters on every page. It never sleeps or polls.
    let filters = DialogueFilters { name: Some("market".into()), ..DialogueFilters::default() };
    let mut dialogues = client.dialogues().page_dialogues(filters);
    while let Some(d) = dialogues.next().await {
        let d = d?;
        println!("{} {} active {:?}", d.id, d.name, d.active_revision);
    }
    // lingara:end
    Ok(())
}
