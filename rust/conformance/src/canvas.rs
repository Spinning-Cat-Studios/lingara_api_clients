//! The seven canvas operations (ADR 16.10.26ak), their inputs read from a
//! `call` block: `params.canvas_id` is the id, `body` the request model, and
//! `listCanvases`' filters are the `page_canvases` step's too.

use lingara::canvas::{CanvasFilters, ListCanvasesParams};
use serde_json::Value;

use crate::compare::Observed;
use crate::events::text;
use crate::observe::json_result;

/// `listCanvases`' or `page_canvases`' `params`, less the cursor.
pub fn filters(params: &Value) -> Result<CanvasFilters, String> {
    let limit = params.get("limit").and_then(Value::as_u64).map(|n| u8::try_from(n).map_err(|_| format!("limit {n}"))).transpose()?;
    let list = |key: &str| params.get(key).and_then(Value::as_array).map(|t| t.iter().filter_map(Value::as_str).map(str::to_owned).collect()).unwrap_or_default();
    Ok(CanvasFilters { limit, name: text(params, "name"), status: text(params, "status"), recipes: list("recipes"), kinds: list("kinds"), scopes: list("scopes") })
}

/// One of the seven canvas operations, called through `client.canvases()`.
pub async fn call(client: &lingara::Client, operation: &str, call: &Value) -> Result<Observed, String> {
    let canvases = client.canvases();
    let params = call.get("params").cloned().unwrap_or(Value::Null);
    let id = text(&params, "canvas_id").unwrap_or_default();
    let body = call.get("body").cloned().unwrap_or(Value::Null);
    Ok(match operation {
        "listCanvases" => {
            let list = ListCanvasesParams { cursor: text(&params, "cursor"), filters: filters(&params)? };
            json_result(canvases.list_canvases(&list).await)
        }
        "createCanvas" => json_result(canvases.create_canvas(&decode(body)?).await),
        "getCanvas" => json_result(canvases.get_canvas(&id).await),
        "renameCanvas" => json_result(canvases.rename_canvas(&id, &decode(body)?).await),
        "saveCanvasDraft" => json_result(canvases.save_canvas_draft(&id, &decode(body)?).await),
        "activateCanvas" => json_result(canvases.activate_canvas(&id, &decode(body)?).await),
        "dryRunCanvasPlan" => json_result(canvases.dry_run_canvas_plan(&decode(body)?).await),
        other => return Err(format!("no operation {other}")),
    })
}

fn decode<T: serde::de::DeserializeOwned>(body: Value) -> Result<T, String> {
    serde_json::from_value(body).map_err(|e| format!("body: {e}"))
}
