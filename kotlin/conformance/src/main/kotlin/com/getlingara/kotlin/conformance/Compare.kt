package com.getlingara.kotlin.conformance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How an observed call is compared with a case's `expect` (conformance/README.md, Comparison
 * rules). Pure: no I/O and no library call.
 */
internal object Compare {
    /** Replaces `{base_url}` in every string of an expected value. */
    fun substitute(
        value: JsonElement,
        baseUrl: String,
    ): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(value.mapValues { substitute(it.value, baseUrl) })
            is JsonArray -> JsonArray(value.map { substitute(it, baseUrl) })
            is JsonPrimitive -> if (value.isString) JsonPrimitive(value.content.replace("{base_url}", baseUrl)) else value
        }

    /** Every difference between one observed call and its expectation. */
    fun compare(
        expect: JsonObject,
        seen: Seen,
    ): List<String> {
        val out = mutableListOf<String>()
        val want = expect.text("outcome")
        if (want != seen.outcome) {
            val detail = seen.variant?.let { " ($it ${canon(seen.fields)})" } ?: ""
            out += "outcome: expected $want, got ${seen.outcome}$detail"
        }
        val observed =
            sortedMapOf(
                "status" to (seen.status?.let { JsonPrimitive(it) } ?: seen.fields?.get("status")),
                "body" to seen.body,
                "events" to JsonArray(seen.events),
                "served_version" to seen.servedVersion?.let { JsonPrimitive(it) },
                "sleeps_s" to JsonArray(seen.sleeps.map { JsonPrimitive(it) }),
                "hook_calls" to JsonArray(seen.hooks),
            )
        observed.forEach { (label, got) -> expect[label]?.let { same(label, it, got, out) } }
        (expect["error"] as? JsonObject)?.let { compareError(it, seen, out) }
        (expect["redacted"] as? JsonArray)?.let { compareRedacted(it, seen, out) }
        return out
    }

    private fun compareError(
        want: JsonObject,
        seen: Seen,
        out: MutableList<String>,
    ) {
        val variant = want.text("variant")
        if (seen.variant == null) {
            out += "error: expected $variant, got none"
            return
        }
        if (seen.variant != variant) out += "error.variant: expected $variant, got ${seen.variant}"
        (want["fields"] as? JsonObject)?.forEach { (name, value) -> same("error.$name", value, seen.fields?.get(name), out) }
    }

    private fun compareRedacted(
        secrets: JsonArray,
        seen: Seen,
        out: MutableList<String>,
    ) {
        for (secret in secrets) {
            val value = (secret as? JsonPrimitive)?.content.orEmpty()
            if (value.isNotEmpty() && seen.renderings.any { it.contains(value) }) {
                out += "redacted: a rendering contains ${value.take(12)}"
            }
        }
    }

    private fun same(
        label: String,
        want: JsonElement,
        got: JsonElement?,
        out: MutableList<String>,
    ) {
        val w = canon(want)
        val g = canon(got)
        if (w != g) out += "$label: expected $w, got $g"
    }

    /** JSON with null-valued keys dropped, keys sorted and every number one decimal spelling. */
    fun canon(value: JsonElement?): String = normalise(value)?.toString() ?: "null"

    private fun normalise(node: JsonElement?): JsonElement? =
        when (node) {
            null, JsonNull -> null
            is JsonObject ->
                JsonObject(
                    node.entries
                        .mapNotNull { (k, v) -> normalise(v)?.let { k to it } }
                        .sortedBy { it.first }
                        .toMap(),
                )
            is JsonArray -> JsonArray(node.map { normalise(it) ?: JsonNull })
            is JsonPrimitive -> if (node.isString) node else number(node)
        }

    /** A number as one decimal spelling, still a JSON number, so `2` and `"2"` stay different. */
    private fun number(node: JsonPrimitive): JsonPrimitive =
        node.content.toBigDecimalOrNull()?.let { JsonPrimitive(it.stripTrailingZeros()) } ?: node
}
