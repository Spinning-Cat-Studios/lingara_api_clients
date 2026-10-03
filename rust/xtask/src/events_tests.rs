//! The events emitter over a fixture view (ADR 30.9.26aa D3).

use serde_json::{Value, json};

use super::{read, render};

/// One outbound type, one inbound type, and the components they name.
fn fixture() -> Value {
    json!({
        "components": { "schemas": {
            "LessonPlanReadyData": { "type": "object" },
            "WorldContextChanged": { "type": "object" },
        } },
        "x-lingara-events": [
            { "type": "lesson_plan.ready", "direction": "out", "arm": "LessonPlanReady",
              "data": "#/components/schemas/LessonPlanReadyData", "transports": ["feed", "stream", "webhook"] },
            { "type": "world.context_changed", "direction": "in",
              "data": "#/components/schemas/WorldContextChanged", "transports": ["inbound"] },
        ],
    })
}

fn rendered(view: &Value) -> String {
    crate::render(render(&read(view).unwrap())).unwrap()
}

/// 30.9.26aa D3: an outbound entry is an arm struct and an `Event` variant
/// routed by its wire type; an inbound entry is an `InboundEvent` variant
/// named after, and holding, its `data` component; `Unknown` closes the
/// union.
#[test]
fn the_fixture_view_renders_the_union_and_the_inbound_type() {
    let code = rendered(&fixture());
    for needle in [
        "pub struct LessonPlanReady {",
        "pub data: super::models::LessonPlanReadyData,",
        "pub struct UnknownEvent {",
        "#[non_exhaustive]\npub enum Event {\n    LessonPlanReady(LessonPlanReady),\n    Unknown(UnknownEvent),\n}",
        "\"lesson_plan.ready\" =>",
        "serde_json::from_value(value).map(Event::LessonPlanReady)",
        "_ => serde_json::from_value(value).map(Event::Unknown),",
        "Event::LessonPlanReady(_) => \"lesson_plan.ready\",",
        "#[serde(rename = \"world.context_changed\")]\n    WorldContextChanged(super::models::WorldContextChanged),",
        "pub fn parse_event(json: &[u8])",
    ] {
        assert!(code.contains(needle), "missing {needle:?} in\n{code}");
    }
    assert!(!code.contains("pub struct WorldContextChanged"), "an inbound entry has no arm type");
}

/// A view with no catalogue renders an empty union, not a refusal.
#[test]
fn a_view_without_events_renders_only_unknown() {
    let code = rendered(&json!({ "components": { "schemas": {} } }));
    assert!(code.contains("pub enum Event {\n    Unknown(UnknownEvent),\n}"), "{code}");
    assert!(code.contains("pub enum InboundEvent {}"), "{code}");
}

/// 30.9.26aa D3: a component named after an arm, or after one of the three
/// generated types, fails codegen rather than shipping two types for one
/// name; so does an entry whose `data` component is missing.
#[test]
fn a_component_named_after_a_generated_type_is_refused() {
    for name in ["LessonPlanReady", "Event", "UnknownEvent", "InboundEvent"] {
        let mut view = fixture();
        view["components"]["schemas"][name] = json!({ "type": "object" });
        let err = read(&view).err().unwrap_or_else(|| panic!("{name} was accepted"));
        assert!(err.contains(name), "{err}");
    }
    let mut view = fixture();
    view["components"]["schemas"].as_object_mut().unwrap().remove("LessonPlanReadyData");
    assert!(read(&view).is_err());
}

/// The committed view's catalogue reads, and every outbound entry has an arm.
#[test]
fn the_committed_view_reads() {
    let view = crate::read_json(&crate::workspace_root().join(crate::VIEW)).unwrap();
    let entries = read(&view).unwrap();
    let catalogue = view["x-lingara-events"].as_array().unwrap();
    assert_eq!(entries.len(), catalogue.len());
    let arms = entries.iter().filter_map(|e| e.arm()).count();
    assert_eq!(arms, catalogue.iter().filter(|e| e["direction"] == "out").count());
}
