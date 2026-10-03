use serde_json::{json, Value};

use crate::build_view;
use super::events;

const S: &str = "#/components/schemas/";

/// An outbound payload: the six envelope fields, `type` a const.
fn outbound(name: &str, data: &str) -> Value {
    json!({
        "type": "object",
        "required": ["id", "type", "created_at", "api_version", "subject", "data"],
        "properties": {
            "id": { "type": "string" },
            "type": { "type": "string", "const": name },
            "created_at": { "type": "string", "format": "date-time" },
            "api_version": { "type": "string" },
            "subject": { "type": "string" },
            "data": { "$ref": format!("{S}{data}") },
        },
    })
}

/// An inbound payload: `{type, data}` and nothing else.
fn inbound(name: &str, data: &str) -> Value {
    json!({
        "type": "object",
        "required": ["type", "data"],
        "properties": { "type": { "type": "string", "const": name }, "data": { "$ref": format!("{S}{data}") } },
    })
}

fn message(name: &str, payload: Value) -> Value {
    json!({ "name": name, "x-i18n": "m", "description": "d", "contentType": "application/json", "payload": payload, "examples": [{ "payload": {} }] })
}

fn op(action: &str, channel: &str, key: &str) -> Value {
    json!({
        "action": action,
        "channel": { "$ref": format!("#/channels/{channel}") },
        "messages": [{ "$ref": format!("#/channels/{channel}/messages/{key}") }],
        "security": [{ "$ref": "#/components/securitySchemes/s" }],
    })
}

fn channel(transport: &str, address: Value, keys: &[(&str, &str)]) -> Value {
    let messages: serde_json::Map<String, Value> =
        keys.iter().map(|(k, m)| (k.to_string(), json!({ "$ref": format!("#/components/messages/{m}") }))).collect();
    json!({ "address": address, "x-lingara-transport": transport, "servers": [{ "$ref": "#/servers/api" }], "messages": messages, "x-i18n": "c" })
}

/// E1's shape, cut down: a ready event on webhook and feed, a webhook-only
/// test event, and one inbound event.
pub(crate) fn catalogue() -> Value {
    json!({
        "asyncapi": "3.0.0",
        "info": { "title": "t", "version": "1" },
        "servers": { "api": { "host": "x", "protocol": "https" } },
        "defaultContentType": "application/json",
        "channels": {
            "webhook": channel("webhook", Value::Null, &[("ready", "Ready"), ("test", "Test")]),
            "feed": channel("feed", json!("/v1/events"), &[("ready", "Ready")]),
            "inbound": channel("inbound", json!("/v1/events"), &[("world", "World")]),
        },
        "operations": {
            "sendReadyWebhook": op("send", "webhook", "ready"),
            "sendTestWebhook": op("send", "webhook", "test"),
            "sendReadyFeed": op("send", "feed", "ready"),
            "receiveWorld": op("receive", "inbound", "world"),
        },
        "components": {
            "schemas": {
                "ReadyData": { "type": "object", "properties": { "plan_id": { "type": "string" } } },
                "TestData": { "type": "object" },
                "WorldContextChanged": { "type": "object", "properties": { "scene": { "type": "string" } } },
            },
            "messages": {
                "Ready": message("lesson_plan.ready", outbound("lesson_plan.ready", "ReadyData")),
                "Test": message("webhook.test", outbound("webhook.test", "TestData")),
                "World": message("world.context_changed", inbound("world.context_changed", "WorldContextChanged")),
            },
            "securitySchemes": { "s": { "type": "oauth2" } },
        },
    })
}

fn inbound_request(tag: Value) -> Value {
    json!({ "oneOf": [{
        "type": "object",
        "properties": { "type": tag, "data": { "$ref": format!("{S}WorldContextChanged") } },
        "required": ["type", "data"],
    }] })
}

/// The OpenAPI half: `sendEvent`'s body is a `$ref` to `InboundEventRequest`.
pub(crate) fn openapi() -> Value {
    json!({
        "openapi": "3.2.0",
        "info": { "title": "t", "version": "1" },
        "paths": {
            "/v1/events": {
                "post": {
                    "operationId": "sendEvent",
                    "requestBody": { "content": { "application/json": { "schema": { "$ref": format!("{S}InboundEventRequest") } } } },
                    "responses": { "202": { "description": "ok" } },
                },
            },
        },
        "components": {
            "schemas": {
                "WorldContextChanged": { "type": "object", "properties": { "scene": { "type": "string" } } },
                "InboundEventRequest": inbound_request(json!({ "type": "string", "const": "world.context_changed" })),
            },
        },
    })
}

fn run(spec: &Value, catalogue: &Value) -> Result<Value, String> {
    let mut doc = spec.clone();
    events(&mut doc, spec, catalogue).map_err(|r| r.0)?;
    Ok(doc)
}

fn refusal(catalogue: &Value) -> String {
    run(&openapi(), catalogue).unwrap_err()
}

// 30.9.26aa AC5
#[test]
fn each_message_is_recorded_with_its_direction() {
    let doc = run(&openapi(), &catalogue()).unwrap();
    assert_eq!(
        doc["x-lingara-events"],
        json!([
            { "type": "lesson_plan.ready", "direction": "out", "arm": "LessonPlanReady", "data": format!("{S}ReadyData"), "transports": ["feed", "webhook"] },
            { "type": "webhook.test", "direction": "out", "arm": "WebhookTest", "data": format!("{S}TestData"), "transports": ["webhook"] },
            { "type": "world.context_changed", "direction": "in", "data": format!("{S}WorldContextChanged"), "transports": ["inbound"] },
        ]),
        "document order is components.messages order; the webhook channel's null address is accepted"
    );

    let mut reordered = catalogue();
    let ops = reordered["operations"].as_object_mut().unwrap();
    let first = ops.shift_remove("sendReadyWebhook").unwrap();
    ops.insert("sendReadyWebhook".into(), first);
    assert_eq!(run(&openapi(), &reordered).unwrap()["x-lingara-events"], doc["x-lingara-events"], "operations order is not record order");

    let mut unknown = catalogue();
    unknown["channels"]["feed"]["x-lingara-transport"] = json!("carrier-pigeon");
    assert!(refusal(&unknown).contains("is not one of"), "an unknown transport is refused");

    let mut disagree = catalogue();
    disagree["operations"]["receiveWorld"]["action"] = json!("send");
    assert!(refusal(&disagree).contains("a send operation on the inbound channel"));
    let mut disagree = catalogue();
    disagree["operations"]["sendReadyFeed"]["action"] = json!("receive");
    assert!(refusal(&disagree).contains("a receive operation on the feed channel"));
}

// 30.9.26aa AC6
#[test]
fn a_payload_that_is_not_its_direction_shape_is_refused() {
    let mut extra = catalogue();
    extra["components"]["messages"]["Ready"]["payload"]["properties"]["extra"] = json!({ "type": "string" });
    assert!(refusal(&extra).contains("/components/messages/Ready/payload: its properties and required"));

    let mut missing = catalogue();
    missing["components"]["messages"]["Ready"]["payload"]["required"] = json!(["id", "type"]);
    assert!(refusal(&missing).contains("/components/messages/Ready/payload"));

    let mut envelope_inbound = catalogue();
    envelope_inbound["components"]["messages"]["World"]["payload"] = outbound("world.context_changed", "WorldContextChanged");
    assert!(refusal(&envelope_inbound).contains("/components/messages/World/payload: its properties and required are not exactly [\"type\", \"data\"]"));

    let mut tag = catalogue();
    tag["components"]["messages"]["Ready"]["payload"]["properties"]["type"]["const"] = json!("lesson_plan.done");
    assert!(refusal(&tag).contains("type is \"lesson_plan.done\", not the message name \"lesson_plan.ready\""));

    let mut by_ref = catalogue();
    by_ref["components"]["messages"]["Ready"]["payload"] = json!({ "$ref": format!("{S}ReadyData") });
    assert!(refusal(&by_ref).contains("/components/messages/Ready/payload"));
}

// 30.9.26aa AC7
#[test]
fn a_schema_is_defined_once() {
    let doc = run(&openapi(), &catalogue()).unwrap();
    let schemas = doc["components"]["schemas"].as_object().unwrap();
    assert_eq!(schemas["ReadyData"], catalogue()["components"]["schemas"]["ReadyData"], "an absent schema is merged");
    assert_eq!(schemas.keys().filter(|k| *k == "WorldContextChanged").count(), 1, "an identical one is merged once");

    let mut reordered = openapi();
    reordered["components"]["schemas"]["WorldContextChanged"] = json!({ "properties": { "scene": { "type": "string" } }, "type": "object" });
    run(&reordered, &catalogue()).expect("key order is not a difference");

    let mut differs = catalogue();
    differs["components"]["schemas"]["WorldContextChanged"]["properties"]["scene"]["type"] = json!("integer");
    assert!(refusal(&differs).contains("/components/schemas/WorldContextChanged: the catalogue's schema differs"));

    let mut external = catalogue();
    external["components"]["schemas"]["TestData"] = json!({ "$ref": "other.json#/Thing" });
    assert!(refusal(&external).contains("is not internal to the catalogue"));
}

// 30.9.26aa AC8
#[test]
fn keys_outside_the_subset_are_refused() {
    for (ptr, key) in [
        ("/operations/sendReadyFeed", "bindings"),
        ("/operations/sendReadyFeed", "traits"),
        ("/channels/feed", "bindings"),
        ("/components/messages/Ready", "traits"),
        ("/components", "operationTraits"),
        ("", "id"),
    ] {
        let mut doc = catalogue();
        doc.pointer_mut(ptr).unwrap()[key] = json!({});
        assert!(refusal(&doc).contains(&format!("{ptr}/{key}: outside the AsyncAPI subset")), "{ptr}/{key}");
    }
    let mut action = catalogue();
    action["operations"]["sendReadyFeed"]["action"] = json!("publish");
    assert!(refusal(&action).contains("\"publish\" is not send or receive"));
    let mut status = catalogue();
    status["channels"]["feed"]["x-lingara-status"] = json!("planned");
    assert!(refusal(&status).contains("/channels/feed/x-lingara-status"));
    let mut content = catalogue();
    content["components"]["messages"]["Ready"]["contentType"] = json!("application/xml");
    assert!(refusal(&content).contains("contentType: not application/json"));

    // Documentation, info, servers, operation security, securitySchemes and
    // message examples are dropped: none of them reaches the view.
    let rendered = run(&openapi(), &catalogue()).unwrap().to_string();
    for absent in ["securitySchemes", "\"servers\"", "examples", "x-i18n"] {
        assert!(!rendered.contains(absent), "{absent} reached the view");
    }
}

// 30.9.26aa AC9
#[test]
fn colliding_event_names_are_refused() {
    let mut two = catalogue();
    two["components"]["messages"]["Ready2"] = message("lesson_plan_ready", outbound("lesson_plan_ready", "ReadyData"));
    two["operations"]["sendReady2"] = op("send", "webhook", "ready2");
    two["channels"]["webhook"]["messages"]["ready2"] = json!({ "$ref": "#/components/messages/Ready2" });
    assert!(refusal(&two).contains("its arm LessonPlanReady collides"));

    let mut component = openapi();
    component["components"]["schemas"]["LessonPlanReady"] = json!({ "type": "object" });
    assert!(run(&component, &catalogue()).unwrap_err().contains("its arm LessonPlanReady collides"));

    for reserved in ["Event", "UnknownEvent", "InboundEvent"] {
        let mut doc = openapi();
        doc["components"]["schemas"][reserved] = json!({ "type": "object" });
        assert!(run(&doc, &catalogue()).unwrap_err().contains("may not take a name D3 generates"), "{reserved}");
    }

    let doc = run(&openapi(), &catalogue()).unwrap();
    let world = &doc["x-lingara-events"][2];
    assert!(world.get("arm").is_none(), "an inbound entry carries no arm");
    assert_eq!(world["data"], format!("{S}WorldContextChanged"), "its constructor is named from its data component");

    let mut shared = catalogue();
    shared["components"]["messages"]["World2"] = message("world.again", inbound("world.again", "WorldContextChanged"));
    shared["operations"]["receiveWorld2"] = op("receive", "inbound", "world2");
    shared["channels"]["inbound"]["messages"]["world2"] = json!({ "$ref": "#/components/messages/World2" });
    assert!(refusal(&shared).contains("another inbound event already uses WorldContextChanged"));
}

// 30.9.26aa AC39
#[test]
fn the_inbound_request_union_leaves_the_view() {
    let doc = run(&openapi(), &catalogue()).unwrap();
    let body = &doc["paths"]["/v1/events"]["post"]["requestBody"]["content"]["application/json"]["schema"];
    assert_eq!(*body, json!({ "type": "object" }));
    assert!(doc["components"]["schemas"].get("InboundEventRequest").is_none());

    let mut as_enum = openapi();
    as_enum["components"]["schemas"]["InboundEventRequest"] = inbound_request(json!({ "type": "string", "enum": ["world.context_changed"] }));
    run(&as_enum, &catalogue()).expect("a one-value enum tag is the same member");

    let mut other_type = openapi();
    other_type["components"]["schemas"]["InboundEventRequest"] = inbound_request(json!({ "type": "string", "const": "world.other" }));
    assert!(run(&other_type, &catalogue()).unwrap_err().contains("are not the catalogue's inbound events"));

    let mut other_data = openapi();
    other_data["components"]["schemas"]["InboundEventRequest"]["oneOf"][0]["properties"]["data"] = json!({ "$ref": format!("{S}Npc") });
    assert!(run(&other_data, &catalogue()).unwrap_err().contains("are not the catalogue's inbound events"));

    let mut extra = openapi();
    let member = extra["components"]["schemas"]["InboundEventRequest"]["oneOf"][0].clone();
    extra["components"]["schemas"]["InboundEventRequest"]["oneOf"].as_array_mut().unwrap().push(member);
    assert!(run(&extra, &catalogue()).unwrap_err().contains("are not the catalogue's inbound events"));

    let mut inline = openapi();
    let union = inline["components"]["schemas"]["InboundEventRequest"].clone();
    inline["paths"]["/v1/events"]["post"]["requestBody"]["content"]["application/json"]["schema"] = union;
    assert!(run(&inline, &catalogue()).unwrap_err().contains("not a single $ref to InboundEventRequest"));
}

/// The A2 `app` channel: request/reply operations and schemas reachable
/// only from its messages, with a `const` tag the 3.0 dialect would refuse.
fn with_app(mut doc: Value) -> Value {
    doc["channels"]["app"] = channel("app", Value::Null, &[("render", "AppRender"), ("card", "AppCard")]);
    let reply = json!({ "channel": { "$ref": "#/channels/app" }, "messages": [{ "$ref": "#/channels/app/messages/card" }] });
    let mut render = op("send", "app", "render");
    render["reply"] = reply;
    doc["operations"]["sendAppRender"] = render;
    let messages = &mut doc["components"]["messages"];
    messages["AppRender"] = message("app.render", json!({ "$ref": format!("{S}AppRenderRequest") }));
    messages["AppCard"] = message("app.card", json!({ "$ref": format!("{S}AppCardReply") }));
    let schemas = &mut doc["components"]["schemas"];
    schemas["AppRenderRequest"] = json!({ "type": "object", "properties": { "kind": { "type": "string", "const": "render" }, "card": { "$ref": format!("{S}Card") } } });
    schemas["AppCardReply"] = json!({ "type": "object", "properties": { "card": { "$ref": format!("{S}Card") }, "data": { "$ref": format!("{S}TestData") } } });
    schemas["Card"] = json!({ "type": "object", "properties": { "title": { "type": "string", "const": "x" } } });
    doc
}

// 30.9.26aa AC44
#[test]
fn an_app_channel_is_skipped_whole() {
    let plain = build_view(&openapi(), Some(&catalogue()), "backend@test").unwrap();
    let app = build_view(&openapi(), Some(&with_app(catalogue())), "backend@test").unwrap();
    assert_eq!(app.v31, plain.v31, "3.1: the app channel left nothing behind");
    assert_eq!(app.v30, plain.v30, "3.0: the app channel left nothing behind");
    assert!(plain.v31["components"]["schemas"].get("TestData").is_some(), "a schema an app message shares stays");

    let mut other = catalogue();
    other["channels"]["fax"] = channel("fax", Value::Null, &[]);
    other["operations"]["sendFax"] = json!({ "action": "send", "channel": { "$ref": "#/channels/fax" }, "messages": [] });
    assert!(refusal(&other).contains("\"fax\""), "any other unknown transport is still refused");
}
