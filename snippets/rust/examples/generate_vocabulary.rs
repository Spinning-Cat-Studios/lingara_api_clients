// lingara:begin auth
use lingara::Client;
// lingara:end

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // lingara:begin auth
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    // lingara:end

    // lingara:begin generateVocabulary
    use lingara::models::{GenerateVocabularyEvent, VocabRequest};
    use std::num::NonZeroU8;

    let request = VocabRequest { level: 2, source_lang: "en".into(), target_lang: "zh".into(), count: NonZeroU8::new(8) };
    let mut stream = client.generate_vocabulary(&request).await?;
    while let Some(event) = stream.next().await {
        if let GenerateVocabularyEvent::Item(item) = event? {
            println!("{} {}", item.word, item.translation);
        }
    }
    // lingara:end
    Ok(())
}
