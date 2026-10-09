//! What the D10 schema cannot say in types: which keys go together.

use crate::case::{Case, Chunk, Exchange, HeaderMatch, Response, Step, Then};

pub fn validate(case: &Case) -> Result<(), String> {
    if case.behaviours.is_empty() {
        return Err("behaviours: at least one of K1–K6 or K5a".into());
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
        same_as_shape(item, i).map_err(|e| format!("exchanges.items[{i}]: {e}"))?;
    }
    Ok(())
}

fn step_shape(step: &Step) -> Result<(), String> {
    let actions = [step.call.is_some(), step.events.is_some(), step.tail.is_some()];
    match (actions.iter().filter(|a| **a).count(), &step.expect, step.advance_clock_s) {
        (1, Some(_), None) => {
            if step.call.as_ref().is_some_and(|c| c.parallel == Some(0)) {
                return Err("parallel: at least 1".into());
            }
            if step.tail.as_ref().is_some_and(|t| t.take == 0) {
                return Err("tail.take: at least 1".into());
            }
            Ok(())
        }
        (0, None, Some(_)) => Ok(()),
        (1, None, None) => Err("a call, events or tail step needs an expect".into()),
        _ => Err("exactly one of `call`, `events` or `tail` with `expect`, or `advance_clock_s`".into()),
    }
}

/// A `same_as` names an earlier item, never itself or a later one: the
/// later one has not matched when this one is checked.
fn same_as_shape(item: &Exchange, index: usize) -> Result<(), String> {
    let matchers = item.request.headers.iter().flatten();
    for (name, m) in matchers {
        if let HeaderMatch::SameAs(same) = m
            && same.request >= index
        {
            return Err(format!("request.headers.{name}.same_as: item {} is not an earlier item", same.request));
        }
    }
    Ok(())
}

fn exchange_shape(item: &Exchange) -> Result<(), String> {
    if item.times == Some(0) {
        return Err("times: at least 1".into());
    }
    let method = item.request.method.as_str();
    if !matches!(method, "GET" | "POST" | "DELETE") {
        return Err(format!("request.method: `{method}` is not GET, POST or DELETE"));
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
