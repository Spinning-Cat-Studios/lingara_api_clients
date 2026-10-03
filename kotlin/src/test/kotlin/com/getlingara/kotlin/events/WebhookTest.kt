package com.getlingara.kotlin.events

import com.getlingara.kotlin.LingaraException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WebhookTest {
    private val vectors: List<JsonObject> =
        Json
            .parseToJsonElement(File(System.getProperty("lingara.vectors")).readText())
            .jsonObject
            .getValue("vectors")
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun webhook(vector: JsonObject): Webhook {
        val secrets = vector.getValue("secrets").jsonArray.map { it.jsonPrimitive.content }
        val now = Instant.ofEpochSecond(vector.getValue("now").jsonPrimitive.long)
        return Webhook(*secrets.toTypedArray(), clock = Clock.fixed(now, ZoneOffset.UTC))
    }

    private fun headers(vector: JsonObject): Map<String, List<String>> =
        vector.getValue("headers").jsonObject.mapValues { listOf(it.value.jsonPrimitive.content) }

    private fun body(vector: JsonObject): ByteArray = vector.text("body").toByteArray()

    /**
     * 30.9.26aa AC27: every shared vector in conformance/vectors/webhook-signatures.json gives its
     * expected result through `verify`: the event's id and type (an `UnknownEvent` where the vector
     * says so), the reason, or a refusal at construction. 30.9.26aa AC45: `verifySignature` passes
     * every `ok` and `malformed_payload` vector and throws every other vector's own reason.
     */
    @Test
    fun everySharedVectorVerifiesAsExpected() {
        for (vector in vectors) {
            val name = vector.text("name")
            val expect = vector.getValue("expect").jsonObject
            when {
                expect["refused"]?.jsonPrimitive?.booleanOrNull == true ->
                    assertFailsWith<IllegalArgumentException>(name) { webhook(vector) }
                "ok" in expect -> expectOk(name, vector, expect.getValue("ok").jsonObject)
                else -> expectError(name, vector, expect.text("error"))
            }
        }
        assertEquals(28, vectors.size)
    }

    private fun expectOk(
        name: String,
        vector: JsonObject,
        ok: JsonObject,
    ) {
        val webhook = webhook(vector)
        val event = webhook.verify(body(vector), headers(vector))
        assertEquals(ok.text("id"), event.id, name)
        assertEquals(ok.text("type"), event.type, name)
        assertEquals(ok["unknown"]?.jsonPrimitive?.booleanOrNull == true, event is UnknownEvent, name)
        webhook.verifySignature(body(vector), headers(vector))
    }

    private fun expectError(
        name: String,
        vector: JsonObject,
        reason: String,
    ) {
        val webhook = webhook(vector)
        val e = assertFailsWith<WebhookVerificationException>(name) { webhook.verify(body(vector), headers(vector)) }
        assertEquals(reason, e.reason.wireName, name)
        if (reason == "malformed_payload") {
            webhook.verifySignature(body(vector), headers(vector))
            return
        }
        val s = assertFailsWith<WebhookVerificationException>(name) { webhook.verifySignature(body(vector), headers(vector)) }
        assertEquals(reason, s.reason.wireName, name)
    }

    /**
     * ADR 30.9.26aa D4: the error sits outside the sealed `LingaraException`, and neither it nor
     * the verifier renders a secret, a signature or the body.
     */
    @Test
    fun theErrorIsOutsideTheRootAndNothingLeaks() {
        assertFalse(LingaraException::class.java.isAssignableFrom(WebhookVerificationException::class.java))
        val vector = vectors.first { it.text("name") == "body-not-json" }
        val webhook = webhook(vector)
        val e = assertFailsWith<WebhookVerificationException> { webhook.verify(body(vector), headers(vector)) }
        val secret =
            vector
                .getValue("secrets")
                .jsonArray[0]
                .jsonPrimitive.content
        for (rendering in listOf(e.toString(), webhook.toString())) {
            assertFalse(secret.removePrefix("lgr_whsec_") in rendering, rendering)
            assertFalse("not json" in rendering, rendering)
            assertFalse("v1," in rendering, rendering)
        }
        assertNull(e.cause)
    }
}
