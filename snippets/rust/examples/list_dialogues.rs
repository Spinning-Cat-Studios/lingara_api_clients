use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin listDialogues
    use lingara::dialogues::{DialogueFilters, ListDialoguesParams};

    // One page; pass its next_cursor back as `cursor` for the next.
    let params = ListDialoguesParams { cursor: None, filters: DialogueFilters { limit: Some(20), name: Some("market".into()) } };
    let page = client.dialogues().list_dialogues(&params).await?;
    for d in &page.items {
        println!("{} {} {}: {} NPCs, {} checkpoints", d.id, d.name, d.ir_id, d.npc_count, d.checkpoint_count);
    }
    if page.has_more {
        println!("more from {:?}", page.next_cursor);
    }
    // lingara:end
    Ok(())
}
