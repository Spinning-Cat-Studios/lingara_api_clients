package com.getlingara.kotlin.conformance

import com.getlingara.kotlin.ApiResponse
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.embed.MintedToken
import com.getlingara.kotlin.embed.createEmbedToken
import com.getlingara.kotlin.embed.deleteEmbedPlayer
import com.getlingara.kotlin.model.EmbedTokenRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.coroutines.cancellation.CancellationException

/**
 * The two embedding JSON calls (ADR 1.10.26w D8): `createEmbedToken`'s [MintedToken] has no
 * serializer, so its body is reported in wire form through the exposing accessor, and
 * `deleteEmbedPlayer`'s success is a `204` with no body. It is also where `Observe`'s JSON dispatch
 * ends, so any other name is reported as an operation the harness does not know.
 */
internal object EmbedSteps {
    suspend fun call(
        c: LingaraClient,
        call: JsonObject,
        operation: String,
    ): Seen =
        when (operation) {
            "createEmbedToken" -> observe { c.createEmbedToken(Observe.body(call, EmbedTokenRequest.serializer())) }
            "deleteEmbedPlayer" -> observe { c.deleteEmbedPlayer(playerRef(call)) }
            else -> Seen().apply { outcome = "harness: no operation $operation" }
        }

    private fun playerRef(call: JsonObject): String =
        (call["params"] as? JsonObject)
            ?.get("player_ref")
            ?.jsonPrimitive
            ?.content
            .orEmpty()

    private suspend fun observe(send: suspend () -> ApiResponse<*>): Seen {
        val response =
            try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Observe.failed(e, emptyList(), null)
            }
        return observed(response)
    }

    private fun observed(response: ApiResponse<*>): Seen =
        Seen().apply {
            outcome = "completed"
            val minted = response.body as? MintedToken
            status = if (minted == null) 204 else 200
            body = minted?.let(::wire)
            servedVersion = response.servedVersion
            // The result's renderings join the redacted scan (ADR 1.10.26w D7).
            renderings += listOf(response.toString(), response.body.toString())
        }

    /** A minted token in wire form: snake_case keys, `expires_in` as whole seconds. */
    private fun wire(minted: MintedToken): JsonElement =
        buildJsonObject {
            put("token", minted.token.exposeSecret())
            put("expires_at", minted.expiresAt)
            put("expires_in", minted.expiresIn.seconds)
            put("subject", minted.subject)
            putJsonArray("scopes") { minted.scopes.forEach { add(it) } }
            put("account_linked", minted.accountLinked)
        }
}
