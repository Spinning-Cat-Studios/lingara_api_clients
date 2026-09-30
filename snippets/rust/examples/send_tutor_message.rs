use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin sendTutorMessage
    use lingara::models::{SendTutorMessageEvent, TutorTurnRequest};

    let request = TutorTurnRequest {
        message: "你好！我想点一杯茶。".into(),
        history: Vec::new(),
        source_lang: "en".into(),
        target_lang: "zh".into(),
        level: None,
        character: None,
        situation: None,
    };
    let mut reply = client.send_tutor_message(&request).await?;
    while let Some(event) = reply.next().await {
        if let SendTutorMessageEvent::Delta(delta) = event? {
            print!("{}", delta.text);
        }
    }
    // lingara:end
    Ok(())
}
