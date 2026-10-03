//! `events.rs` from `x-lingara-events` (ADR 30.9.26aa D3).
//!
//! One struct per outbound arm, holding the envelope fields and its typed
//! `data`; `UnknownEvent` beside them; the `#[non_exhaustive]` `Event` enum
//! with a written `Deserialize` that reads `type` first and routes an unknown
//! one to `Event::Unknown` (serde's `#[serde(other)]` cannot carry data); and
//! `InboundEvent`, adjacently tagged on `type` and `data`, one variant per
//! inbound entry named after its `data` component.

use proc_macro2::{Ident, Span, TokenStream};
use quote::quote;
use serde_json::Value;

const SCHEMA_REF: &str = "#/components/schemas/";

/// The three names this file generates, which no component may take.
const RESERVED: [&str; 3] = ["Event", "UnknownEvent", "InboundEvent"];

/// One `x-lingara-events` entry, as the emitter needs it.
pub struct Entry {
    wire: String,
    /// The arm type for an outbound entry, the `data` component for an
    /// inbound one: the variant's name either way.
    name: String,
    data: String,
    outbound: bool,
}

impl Entry {
    /// The arm types this file defines: typify must never define them too.
    pub fn arm(&self) -> Option<&str> {
        self.outbound.then_some(self.name.as_str())
    }
}

/// Every entry, in the view's order. A view without the key has an empty
/// catalogue (ADR 30.9.26aa D1).
pub fn read(view: &Value) -> Result<Vec<Entry>, String> {
    let Some(entries) = view.get("x-lingara-events") else { return Ok(Vec::new()) };
    let entries = entries.as_array().ok_or("x-lingara-events is not a list")?;
    let schemas = view.pointer("/components/schemas").and_then(Value::as_object).ok_or("the view has no components.schemas")?;
    let read: Vec<Entry> = entries.iter().map(read_one).collect::<Result<_, _>>()?;
    for entry in &read {
        if !schemas.contains_key(&entry.data) {
            return Err(format!("{}: no component {}", entry.wire, entry.data));
        }
    }
    for name in read.iter().filter_map(Entry::arm).chain(RESERVED) {
        if schemas.contains_key(name) {
            return Err(format!("the component {name} is named after a generated event type; the view must rename it"));
        }
    }
    Ok(read)
}

fn read_one(entry: &Value) -> Result<Entry, String> {
    let text = |key: &str| entry.get(key).and_then(Value::as_str);
    let wire = text("type").ok_or("x-lingara-events entry without type")?.to_owned();
    let data = text("data").ok_or(format!("{wire}: no data"))?;
    let data = data.strip_prefix(SCHEMA_REF).ok_or(format!("{wire}: {data} is not under {SCHEMA_REF}"))?.to_owned();
    let (outbound, name) = match text("direction") {
        Some("out") => (true, text("arm").ok_or(format!("{wire}: an outbound entry without arm"))?.to_owned()),
        Some("in") => (false, data.clone()),
        other => return Err(format!("{wire}: direction {other:?} is neither out nor in")),
    };
    Ok(Entry { wire, name, data, outbound })
}

pub fn render(entries: &[Entry]) -> TokenStream {
    let outbound: Vec<&Entry> = entries.iter().filter(|e| e.outbound).collect();
    let inbound: Vec<&Entry> = entries.iter().filter(|e| !e.outbound).collect();
    let arms = outbound.iter().map(|e| arm_struct(e));
    let unknown = unknown_struct();
    let event = event_enum(&outbound);
    let accessors = accessors(&outbound);
    let parse = parse(&outbound);
    let inbound = inbound_enum(&inbound);
    quote! {
        #(#arms)*
        #unknown
        #event
        #accessors
        #parse
        #inbound
    }
}

fn arm_struct(e: &Entry) -> TokenStream {
    let doc = format!(" `{}`, with its typed `data`: [`Event::{}`].", e.wire, e.name);
    let (name, data) = (ident(&e.name), ident(&e.data));
    quote! {
        #[doc = #doc]
        #[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
        pub struct #name {
            /// `lgr_evt_…`: the key to deduplicate deliveries by.
            pub id: String,
            pub created_at: String,
            /// The API version `data` is rendered at: your client's pin.
            pub api_version: String,
            pub subject: String,
            pub data: super::models::#data,
        }
    }
}

/// The same for every catalogue: written here so the event types sit in one
/// generated file.
fn unknown_struct() -> TokenStream {
    quote! {
        /// An event type newer than this crate. Acknowledge it (answer a
        /// webhook `2xx`) and log it: the catalogue only grows, and an
        /// unacknowledged delivery is retried for about a day.
        #[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
        pub struct UnknownEvent {
            pub id: String,
            #[serde(rename = "type")]
            pub type_: String,
            pub created_at: String,
            pub api_version: String,
            pub subject: String,
            pub data: serde_json::Value,
        }
    }
}

fn event_enum(outbound: &[&Entry]) -> TokenStream {
    let variants = outbound.iter().map(|e| {
        let name = ident(&e.name);
        quote! { #name(#name), }
    });
    quote! {
        /// Every event Lingara sends, on any door: a webhook, the feed or
        /// the tail. `Unknown` holds a type newer than this crate.
        #[derive(Clone, Debug)]
        #[non_exhaustive]
        pub enum Event {
            #(#variants)*
            Unknown(UnknownEvent),
        }
    }
}

fn accessors(outbound: &[&Entry]) -> TokenStream {
    let field = |field: &str| {
        let field = ident(field);
        let arms = outbound.iter().map(|e| {
            let name = ident(&e.name);
            quote! { Event::#name(e) => &e.#field, }
        });
        quote! { match self { #(#arms)* Event::Unknown(e) => &e.#field, } }
    };
    let types = outbound.iter().map(|e| {
        let (name, wire) = (ident(&e.name), &e.wire);
        quote! { Event::#name(_) => #wire, }
    });
    let (id, created_at, api_version, subject) = (field("id"), field("created_at"), field("api_version"), field("subject"));
    quote! {
        impl Event {
            /// `lgr_evt_…`: the key to deduplicate deliveries by.
            pub fn id(&self) -> &str { #id }
            /// The wire type, such as `lesson_plan.ready`.
            pub fn event_type(&self) -> &str { match self { #(#types)* Event::Unknown(e) => &e.type_, } }
            pub fn created_at(&self) -> &str { #created_at }
            /// The API version `data` is rendered at: your client's pin.
            pub fn api_version(&self) -> &str { #api_version }
            pub fn subject(&self) -> &str { #subject }
        }
    }
}

fn parse(outbound: &[&Entry]) -> TokenStream {
    let arms = outbound.iter().map(|e| {
        let (name, wire) = (ident(&e.name), &e.wire);
        quote! { #wire => serde_json::from_value(value).map(Event::#name), }
    });
    quote! {
        /// Reads `type` first: a known type decodes into its arm, and its
        /// `data` failing to decode is an error; any other type is
        /// `Event::Unknown`, never an error.
        impl<'de> serde::Deserialize<'de> for Event {
            fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
                let value = <serde_json::Value as serde::Deserialize>::deserialize(deserializer)?;
                let type_ = value.get("type").and_then(serde_json::Value::as_str).unwrap_or_default().to_owned();
                let event = match type_.as_str() {
                    #(#arms)*
                    _ => serde_json::from_value(value).map(Event::Unknown),
                };
                event.map_err(serde::de::Error::custom)
            }
        }

        /// One event envelope, from its raw JSON bytes.
        pub fn parse_event(json: &[u8]) -> Result<Event, serde_json::Error> {
            serde_json::from_slice(json)
        }
    }
}

fn inbound_enum(inbound: &[&Entry]) -> TokenStream {
    let variants = inbound.iter().map(|e| {
        let (name, wire) = (ident(&e.name), &e.wire);
        quote! { #[serde(rename = #wire)] #name(super::models::#name), }
    });
    quote! {
        /// An event your game sends with `Client::send_event`: one variant
        /// per inbound type, each holding its `data`. It serialises as
        /// `{type, data}`; the server assigns the rest.
        #[derive(Clone, Debug, serde::Deserialize, serde::Serialize)]
        #[serde(tag = "type", content = "data")]
        #[non_exhaustive]
        pub enum InboundEvent { #(#variants)* }
    }
}

fn ident(name: &str) -> Ident {
    Ident::new(name, Span::call_site())
}

#[cfg(test)]
#[path = "events_tests.rs"]
mod tests;
