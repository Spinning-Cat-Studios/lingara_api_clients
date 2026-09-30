//! `streams.rs` from `x-lingara-streams` (ADR 29.9.26p D2).
//!
//! typify over the unions' `$ref` branches would emit an untagged enum of
//! `{event, data}` wrapper structs, putting every payload one `.data` deeper.
//! So each event enum is written here: adjacently tagged on `event` and
//! `data`, one variant per event name in source order, holding that branch's
//! `data` type.

use proc_macro2::{Ident, Span, TokenStream};
use quote::quote;
use serde_json::Value;

const SCHEMA_REF: &str = "#/components/schemas/";
const PARAMETER_REF: &str = "#/components/parameters/";

/// One stream operation, as the route `const` and the event enum need it.
pub struct Stream {
    operation_id: String,
    method: String,
    path: String,
    request_body: Option<String>,
    path_params: Vec<String>,
    union: String,
    /// Event name and the branch component that carries it, in source order.
    events: Vec<Event>,
    /// Each `endsOn` event and its outcome, in source order (ADR 29.9.26ai D2).
    ends: Vec<(String, &'static str)>,
}

struct Event {
    name: String,
    branch: String,
    data: String,
}

impl Stream {
    /// The union and its branch components: typify never sees these.
    pub fn component_names(&self) -> impl Iterator<Item = &str> {
        std::iter::once(self.union.as_str()).chain(self.events.iter().map(|e| e.branch.as_str()))
    }
}

pub fn read(view: &Value) -> Result<Vec<Stream>, String> {
    let entries = view.get("x-lingara-streams").and_then(Value::as_array).ok_or("the view has no x-lingara-streams")?;
    entries.iter().map(|entry| read_one(view, entry)).collect()
}

fn read_one(view: &Value, entry: &Value) -> Result<Stream, String> {
    let text = |key: &str| entry.get(key).and_then(Value::as_str).map(str::to_owned).ok_or(format!("x-lingara-streams entry without {key}"));
    let operation_id = text("operationId")?;
    let union = text("union")?;
    let names = entry.get("events").and_then(Value::as_array).ok_or(format!("{operation_id}: no events"))?;
    let events: Vec<Event> = names
        .iter()
        .map(|name| event(view, &union, name.as_str().unwrap_or_default()))
        .collect::<Result<_, _>>()?;
    let ends = ends(entry, &operation_id, &events)?;
    let request_body = match entry.get("requestBody") {
        Some(Value::String(r)) => Some(strip(r, SCHEMA_REF)?.to_owned()),
        _ => None,
    };
    Ok(Stream {
        method: text("method")?.to_uppercase(),
        path: text("path")?,
        request_body,
        path_params: path_params(view, entry)?,
        union,
        events,
        ends,
        operation_id,
    })
}

/// CONTRACT.md K5's rule over the entry's `endsOn` (ADR 29.9.26ai D2): its
/// `error` event raises, an ending event whose payload is `Done` ends
/// unyielded, and any other ending event is yielded and then ends. Each is
/// the name of a `stream::Outcome` variant.
fn ends(entry: &Value, operation_id: &str, events: &[Event]) -> Result<Vec<(String, &'static str)>, String> {
    let no_ends = || format!("{operation_id}: no endsOn; the view predates ADR 29.9.26ai");
    let ends_on = entry.get("endsOn").and_then(Value::as_array).filter(|e| !e.is_empty()).ok_or_else(no_ends)?;
    let error = entry.get("error").and_then(Value::as_str).ok_or(format!("{operation_id}: no error event"))?;
    let mut out = Vec::new();
    for name in ends_on.iter().map(|e| e.as_str().unwrap_or_default()) {
        let event = events.iter().find(|e| e.name == name).ok_or(format!("{operation_id}: endsOn names unknown event {name}"))?;
        let outcome = if name == error {
            "Raise"
        } else if event.data == "Done" {
            "End"
        } else {
            "Yield"
        };
        out.push((name.to_owned(), outcome));
    }
    Ok(out)
}

/// The branch of `union` whose `event` is `name`, and its `data` type.
fn event(view: &Value, union: &str, name: &str) -> Result<Event, String> {
    let schemas = view.pointer("/components/schemas").ok_or("no components.schemas")?;
    let branches = schemas.pointer(&format!("/{union}/oneOf")).and_then(Value::as_array).ok_or(format!("{union}: no oneOf"))?;
    for branch in branches {
        let branch = strip(branch.get("$ref").and_then(Value::as_str).unwrap_or_default(), SCHEMA_REF)?;
        let props = schemas.pointer(&format!("/{branch}/properties")).ok_or(format!("{branch}: no properties"))?;
        if props.pointer("/event/enum/0").and_then(Value::as_str) != Some(name) {
            continue;
        }
        let data = props.pointer("/data/$ref").and_then(Value::as_str).ok_or(format!("{branch}: data is not a $ref"))?;
        return Ok(Event { name: name.to_owned(), branch: branch.to_owned(), data: strip(data, SCHEMA_REF)?.to_owned() });
    }
    Err(format!("{union}: no branch for event {name}"))
}

/// The names of the entry's path parameters, in order; headers are skipped.
fn path_params(view: &Value, entry: &Value) -> Result<Vec<String>, String> {
    let refs = entry.get("parameters").and_then(Value::as_array).cloned().unwrap_or_default();
    let mut names = Vec::new();
    for r in refs {
        let key = strip(r.as_str().unwrap_or_default(), PARAMETER_REF)?;
        let param = view.pointer(&format!("/components/parameters/{key}")).ok_or(format!("no parameter {key}"))?;
        if param.get("in").and_then(Value::as_str) == Some("path") {
            names.push(param.get("name").and_then(Value::as_str).ok_or(format!("{key}: no name"))?.to_owned());
        }
    }
    Ok(names)
}

fn strip<'a>(reference: &'a str, prefix: &str) -> Result<&'a str, String> {
    reference.strip_prefix(prefix).ok_or(format!("{reference} is not under {prefix}"))
}

pub fn render(streams: &[Stream]) -> TokenStream {
    let enums = streams.iter().map(event_enum);
    let routes = streams.iter().map(route);
    let consts = streams.iter().map(|s| ident(&screaming(&s.operation_id)));
    let count = proc_macro2::Literal::usize_unsuffixed(streams.len());
    quote! {
        use crate::stream::{Outcome, StreamRoute};
        #(#enums)*
        #(#routes)*
        /// Every stream operation, in the view's order: what the unit test
        /// holds the terminal table and the client's methods against.
        #[cfg(test)]
        pub(crate) const ROUTES: [StreamRoute; #count] = [#(#consts),*];
    }
}

fn event_enum(s: &Stream) -> TokenStream {
    let doc = format!(" The events `{}` yields (`{}` in the spec).", s.operation_id, s.union);
    let name = ident(&s.union);
    let variants = s.events.iter().map(|e| {
        let (tag, variant, data) = (&e.name, ident(&pascal(&e.name)), ident(&e.data));
        quote! { #[serde(rename = #tag)] #variant(super::models::#data), }
    });
    quote! {
        #[doc = #doc]
        #[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
        #[serde(tag = "event", content = "data")]
        #[non_exhaustive]
        pub enum #name { #(#variants)* }
    }
}

fn route(s: &Stream) -> TokenStream {
    let doc = format!(" `{}`: `{} {}`.", s.operation_id, s.method, s.path);
    let name = ident(&screaming(&s.operation_id));
    let (op, method, path) = (&s.operation_id, &s.method, &s.path);
    let body = match &s.request_body {
        Some(body) => quote! { Some(#body) },
        None => quote! { None },
    };
    let params = &s.path_params;
    let events = s.events.iter().map(|e| &e.name);
    let ends = s.ends.iter().map(|(name, outcome)| {
        let outcome = ident(outcome);
        quote! { (#name, Outcome::#outcome) }
    });
    quote! {
        #[doc = #doc]
        pub(crate) const #name: StreamRoute = StreamRoute {
            operation_id: #op,
            method: #method,
            path: #path,
            request_body: #body,
            path_params: &[#(#params),*],
            events: &[#(#events),*],
            ends: &[#(#ends),*],
        };
    }
}

fn ident(name: &str) -> Ident {
    Ident::new(name, Span::call_site())
}

/// `started` → `Started`, `not_found` → `NotFound`.
fn pascal(name: &str) -> String {
    name.split(['_', '-'])
        .map(|word| {
            let mut chars = word.chars();
            chars.next().map(|c| c.to_uppercase().chain(chars).collect::<String>()).unwrap_or_default()
        })
        .collect()
}

/// `generateVocabulary` → `GENERATE_VOCABULARY`.
fn screaming(camel: &str) -> String {
    let mut out = String::new();
    for c in camel.chars() {
        if c.is_uppercase() && !out.is_empty() {
            out.push('_');
        }
        out.extend(c.to_uppercase());
    }
    out
}

#[cfg(test)]
#[path = "streams_tests.rs"]
mod tests;
