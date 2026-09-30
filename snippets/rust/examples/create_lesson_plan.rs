use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin createLessonPlan
    use lingara::models::{CreateLessonPlanEvent, LessonPlanCreateRequest};

    let request = LessonPlanCreateRequest {
        context: "ordering at a night market".into(),
        source_lang: "en".into(),
        target_lang: "zh".into(),
        level: 2,
    };
    let mut stream = client.create_lesson_plan(&request).await?;
    while let Some(event) = stream.next().await {
        match event? {
            CreateLessonPlanEvent::Started(started) => println!("plan {}", started.plan_id),
            CreateLessonPlanEvent::Phase(phase) => println!("working: {}", phase.phase),
            CreateLessonPlanEvent::Result(result) => println!("{}", result.plan.title.unwrap_or_default()),
            _ => {}
        }
    }
    // lingara:end
    Ok(())
}
