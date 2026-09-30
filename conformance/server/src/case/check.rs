//! What the D10 schema cannot say in types: which keys go together.

use crate::case::{Case, Chunk, Exchange, Response, Step, Then};

pub fn validate(case: &Case) -> Result<(), String> {
    if case.behaviours.is_empty() {
        return Err("behaviours: at least one of K1–K6".into());
    }
    if case.steps.is_empty() {
        return Err("steps: at least one".into());
    }
    for (i, step) in case.steps.iter().enumerate() {
        step_shape(step).map_err(|e| format!("steps[{i}]: {e}"))?;
    }
    let items = case.exchanges.as_ref().map_or(&[][..], |x| &x.items[..]);
    for (i, item) in items.iter().enumerate() {
        exchange_shape(item).map_err(|e| format!("exchanges.items[{i}]: {e}"))?;
    }
    Ok(())
}

fn step_shape(step: &Step) -> Result<(), String> {
    match (&step.call, &step.expect, step.advance_clock_s) {
        (Some(call), Some(_), None) => {
            if call.parallel == Some(0) {
                return Err("parallel: at least 1".into());
            }
            Ok(())
        }
        (None, None, Some(_)) => Ok(()),
        (Some(_), None, None) => Err("a call needs an expect".into()),
        _ => Err("exactly one of `call` + `expect`, or `advance_clock_s`".into()),
    }
}

fn exchange_shape(item: &Exchange) -> Result<(), String> {
    if item.times == Some(0) {
        return Err("times: at least 1".into());
    }
    let method = item.request.method.as_str();
    if method != "GET" && method != "POST" {
        return Err(format!("request.method: `{method}` is not GET or POST"));
    }
    if !item.request.path.starts_with('/') || item.request.path.contains('?') {
        return Err("request.path: absolute, with the query in `query`".into());
    }
    if let Some(headers) = &item.request.headers
        && let Some(name) = headers.keys().find(|k| k.to_ascii_lowercase() != **k)
    {
        return Err(format!("request.headers: `{name}` is not lowercase"));
    }
    response_shape(&item.response).map_err(|e| format!("response: {e}"))
}

fn response_shape(response: &Response) -> Result<(), String> {
    let bodies = [response.json.is_some(), response.text.is_some(), response.sse.is_some()];
    if bodies.iter().filter(|b| **b).count() > 1 {
        return Err("at most one of `json`, `text`, `sse`".into());
    }
    if let Some(headers) = &response.headers {
        for (name, value) in headers {
            if !(value.is_string() || value.is_number()) {
                return Err(format!("headers.{name}: a string or a number"));
            }
        }
    }
    let Some(sse) = &response.sse else { return Ok(()) };
    for (i, chunk) in sse.chunks.iter().enumerate() {
        if let Chunk::Hex(hex) = chunk {
            decode_hex(&hex.hex).map_err(|e| format!("sse.chunks[{i}]: {e}"))?;
        }
    }
    match (sse.then, sse.disconnect_within_ms) {
        (Then::Hold, None) => Err("sse: `hold` needs `disconnect_within_ms`".into()),
        (Then::Close | Then::Reset, Some(_)) => {
            Err("sse: `disconnect_within_ms` applies to `hold` only".into())
        }
        _ => Ok(()),
    }
}

/// `"e4bd"` → `[0xe4, 0xbd]`.
pub fn decode_hex(hex: &str) -> Result<Vec<u8>, String> {
    if !hex.len().is_multiple_of(2) || hex.is_empty() || !hex.is_ascii() {
        return Err(format!("hex `{hex}`: an even, non-zero number of digits"));
    }
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).map_err(|_| format!("hex `{hex}`: not hex")))
        .collect()
}
