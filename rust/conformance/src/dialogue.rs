//! The seven dialogue operations (ADR 29.10.26c), their inputs read from a
//! `call` block: `params.dialogue_id` is the id, `body` the request model,
//! and `listDialogues`' filters are the `page_dialogues` step's too. An
//! export is reported under `body` as `{revision, etag, ir_json}`, or
//! `{not_modified: true, revision, etag}` (D7).

use lingara::dialogues::{DialogueFilters, ExportDialogueOptions, ExportedDialogue, ListDialoguesParams};
use lingara::{ApiResponse, Error};
use serde_json::{Value, json};

use crate::compare::Observed;
use crate::events::text;
use crate::observe::{failed, json_result};

/// `listDialogues`' or `page_dialogues`' `params`, less the cursor.
pub fn filters(params: &Value) -> Result<DialogueFilters, String> {
    let limit = params.get("limit").and_then(Value::as_u64).map(|n| u8::try_from(n).map_err(|_| format!("limit {n}"))).transpose()?;
    Ok(DialogueFilters { limit, name: text(params, "name") })
}

/// One of the seven dialogue operations, called through `client.dialogues()`.
pub async fn call(client: &lingara::Client, operation: &str, call: &Value) -> Result<Observed, String> {
    let dialogues = client.dialogues();
    let params = call.get("params").cloned().unwrap_or(Value::Null);
    let id = text(&params, "dialogue_id").unwrap_or_default();
    let body = call.get("body").cloned().unwrap_or(Value::Null);
    Ok(match operation {
        "listDialogues" => {
            let list = ListDialoguesParams { cursor: text(&params, "cursor"), filters: filters(&params)? };
            json_result(dialogues.list_dialogues(&list).await)
        }
        "createDialogue" => json_result(dialogues.create_dialogue(&decode(body)?).await),
        "getDialogue" => json_result(dialogues.get_dialogue(&id).await),
        "renameDialogue" => json_result(dialogues.rename_dialogue(&id, &decode(body)?).await),
        "saveDialogueDraft" => json_result(dialogues.save_dialogue_draft(&id, &decode(body)?).await),
        "activateDialogue" => json_result(dialogues.activate_dialogue(&id, &decode(body)?).await),
        "exportDialogue" => {
            let options = ExportDialogueOptions { revision: text(&params, "revision"), layout: text(&params, "layout"), if_none_match: text(&params, "if_none_match") };
            exported(dialogues.export_dialogue(&id, &options).await)
        }
        other => return Err(format!("no operation {other}")),
    })
}

/// An export's result, its arm reported as D7's object.
fn exported(result: Result<ApiResponse<ExportedDialogue>, Error>) -> Observed {
    let res = match result {
        Ok(res) => res,
        Err(err) => return failed(err, Vec::new(), None),
    };
    let served_version = res.served_version().map(str::to_owned);
    let (status, body) = match res.into_inner() {
        ExportedDialogue::Body { revision, etag, ir_json } => (200, json!({ "revision": revision, "etag": etag, "ir_json": ir_json })),
        ExportedDialogue::NotModified { revision, etag } => (304, json!({ "not_modified": true, "revision": revision, "etag": etag })),
    };
    Observed { outcome: "completed", status: Some(status), body: Some(body), served_version, ..Observed::default() }
}

fn decode<T: serde::de::DeserializeOwned>(body: Value) -> Result<T, String> {
    serde_json::from_value(body).map_err(|e| format!("body: {e}"))
}
