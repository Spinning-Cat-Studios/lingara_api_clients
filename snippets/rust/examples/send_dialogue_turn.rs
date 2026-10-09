use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let mut history = Vec::new();

    // lingara:begin sendDialogueTurn
    use std::num::NonZeroU8;

    use lingara::models::{DialogueEntry, DialogueTurnRequest, Npc, SendDialogueTurnEvent, Speaker};

    // One turn: at most 12 history entries, each and the line at most 500
    // characters. A turn is never retried, since each attempt spends NPC cells.
    let line = "饺子多少钱？";
    let request = DialogueTurnRequest {
        npc: Npc { name: "Auntie Lin".parse()?, persona: Some("a street-food vendor who likes to haggle".parse()?) },
        source_lang: "en".into(),
        target_lang: "zh".into(),
        level: NonZeroU8::new(3).ok_or("level")?,
        line: line.parse()?,
        history: history.clone(),
        scene: None,
    };
    let mut turn = client.send_dialogue_turn(&request).await?;
    let mut reply = String::new();
    while let Some(event) = turn.next().await {
        if let SendDialogueTurnEvent::Delta(delta) = event? {
            print!("{}", delta.text);
            reply.push_str(&delta.text);
        }
    }
    // Send the reply back next turn, cut to its first 500 characters.
    let npc: String = reply.chars().take(500).collect();
    history.push(DialogueEntry { speaker: Speaker::Player, text: line.parse()? });
    history.push(DialogueEntry { speaker: Speaker::Npc, text: npc.parse()? });
    if history.len() > 12 {
        history.drain(..history.len() - 12);
    }
    // lingara:end
    Ok(())
}
