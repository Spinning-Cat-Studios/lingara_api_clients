use lingara::Client;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        .build()?;

    // lingara:begin dryRunCanvasPlan
    use lingara::models::{CanvasPlan, CanvasVerdictKind};

    // Judges a plan without applying it. A refused op is a verdict in a 200
    // answer, never an error: branch on its code, and show its English error
    // only as a fallback.
    let plan: CanvasPlan = serde_json::from_value(serde_json::json!({ "ops": [
        { "handle": "c1", "kind": "client", "op": "create", "body": { "name": "Lesson server", "scopes": ["events:read"] } },
        { "handle": "w1", "kind": "webhook", "op": "create", "client": { "handle": "c1" },
          "body": { "url": "https://hooks.example.com/lingara", "event_types": ["lesson_plan.ready"] } }
    ] }))?;
    let run = client.canvases().dry_run_canvas_plan(&plan).await?;
    for v in &run.verdicts {
        match v.verdict {
            CanvasVerdictKind::Refused => println!("{} refused: {:?} {:?}", v.handle, v.code, v.error),
            _ => println!("{} {} {:?}", v.handle, v.verdict, v.changes),
        }
    }
    if !run.ok {
        println!("fix the refused ops before you apply");
    }
    // lingara:end
    Ok(())
}
