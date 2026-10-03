package com.getlingara.conformance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How an observed call is compared with a case's {@code expect} (conformance/README.md, Comparison
 * rules). Pure: no I/O and no library call.
 */
final class Compare {
  private Compare() {}

  /** Replaces {@code {base_url}} in every string of an expected value. */
  static JsonNode substitute(JsonNode value, String baseUrl) {
    if (value.isTextual()) {
      return TextNode.valueOf(value.asText().replace("{base_url}", baseUrl));
    }
    if (value.isArray()) {
      ArrayNode out = Harness.JSON.createArrayNode();
      value.forEach(item -> out.add(substitute(item, baseUrl)));
      return out;
    }
    if (value.isObject()) {
      ObjectNode out = Harness.JSON.createObjectNode();
      value.properties().forEach(e -> out.set(e.getKey(), substitute(e.getValue(), baseUrl)));
      return out;
    }
    return value;
  }

  /** Every difference between one observed call and its expectation. */
  static List<String> compare(JsonNode expect, Observe.Seen seen) {
    List<String> out = new ArrayList<>();
    String want = expect.path("outcome").asText();
    if (!want.equals(seen.outcome)) {
      String detail =
          seen.variant == null ? "" : " (" + seen.variant + " " + canon(seen.fields) + ")";
      out.add("outcome: expected " + want + ", got " + seen.outcome + detail);
    }
    Object status = seen.status;
    if (status == null && seen.fields != null) {
      status = seen.fields.get("status");
    }
    Map<String, Object> observed = new TreeMap<>();
    observed.put("status", status);
    observed.put("body", seen.body);
    observed.put("events", seen.events);
    observed.put("served_version", seen.servedVersion);
    observed.put("sleeps_s", seen.sleeps);
    observed.put("hook_calls", seen.hooks);
    observed.put("event_ids", seen.eventIds);
    observed.put("unknown_types", seen.unknownTypes);
    observed.forEach(
        (label, got) -> {
          if (expect.has(label)) {
            same(label, expect.get(label), got, out);
          }
        });
    if (expect.path("error").isObject()) {
      compareError(expect.path("error"), seen, out);
    }
    compareRedacted(expect.path("redacted"), seen, out);
    compareCursor(expect.path("cursor"), seen, out);
    return out;
  }

  /** {@code expect.cursor}: a matcher on an event helper's final cursor. */
  private static void compareCursor(JsonNode want, Observe.Seen seen, List<String> out) {
    if (want.has("equals")) {
      same("cursor", want.get("equals"), seen.cursor, out);
    } else if (want.path("absent").asBoolean() && seen.cursor != null) {
      out.add("cursor: expected absent, got " + seen.cursor);
    }
  }

  private static void compareError(JsonNode want, Observe.Seen seen, List<String> out) {
    String variant = want.path("variant").asText();
    if (seen.variant == null) {
      out.add("error: expected " + variant + ", got none");
      return;
    }
    if (!seen.variant.equals(variant)) {
      out.add("error.variant: expected " + variant + ", got " + seen.variant);
    }
    for (Map.Entry<String, JsonNode> field : want.path("fields").properties()) {
      same("error." + field.getKey(), field.getValue(), seen.fields.get(field.getKey()), out);
    }
  }

  private static void compareRedacted(JsonNode secrets, Observe.Seen seen, List<String> out) {
    for (JsonNode secret : secrets) {
      String value = secret.asText();
      boolean leaked =
          !value.isEmpty() && seen.renderings.stream().anyMatch(r -> r.contains(value));
      if (leaked) {
        out.add(
            "redacted: a rendering contains " + value.substring(0, Math.min(12, value.length())));
      }
    }
  }

  private static void same(String label, JsonNode want, Object got, List<String> out) {
    String w = canon(want);
    String g = canon(got);
    if (!w.equals(g)) {
      out.add(label + ": expected " + w + ", got " + g);
    }
  }

  /** JSON with null-valued keys dropped, keys sorted and every number one decimal spelling. */
  static String canon(Object value) {
    try {
      return Harness.JSON.writeValueAsString(normalise(Harness.JSON.valueToTree(value)));
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return "<unencodable: " + e + ">";
    }
  }

  private static JsonNode normalise(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    if (node.isNumber()) {
      return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
    }
    if (node.isArray()) {
      ArrayNode out = Harness.JSON.createArrayNode();
      node.forEach(item -> out.add(normalise(item)));
      return out;
    }
    if (node.isObject()) {
      Map<String, JsonNode> sorted = new TreeMap<>();
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        JsonNode value = normalise(e.getValue());
        if (value != null) {
          sorted.put(e.getKey(), value);
        }
      }
      ObjectNode out = Harness.JSON.createObjectNode();
      sorted.forEach(out::set);
      return out;
    }
    return node;
  }
}
