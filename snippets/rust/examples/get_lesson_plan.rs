use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;
    let plan_id = std::env::args().nth(1).unwrap_or_else(|| "3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37".into());

    // lingara:begin getLessonPlan
    let plan = client.get_lesson_plan(&plan_id).await?;
    println!("{:?} {}", plan.status, plan.title.as_deref().unwrap_or("(untitled)"));
    // lingara:end
    Ok(())
}
