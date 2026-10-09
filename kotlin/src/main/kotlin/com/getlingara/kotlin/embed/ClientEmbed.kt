package com.getlingara.kotlin.embed

import com.getlingara.kotlin.ApiResponse
import com.getlingara.kotlin.EventStream
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.TransportException
import com.getlingara.kotlin.TransportKind
import com.getlingara.kotlin.internal.Call
import com.getlingara.kotlin.internal.LingaraJson
import com.getlingara.kotlin.internal.Streams
import com.getlingara.kotlin.internal.encodeSegment
import com.getlingara.kotlin.model.DialogueTurnRequest
import com.getlingara.kotlin.model.EmbedTokenRequest
import com.getlingara.kotlin.model.SendDialogueTurnEvent
import kotlinx.serialization.json.JsonObject

// The three embedding operations (ADR 1.10.26w) are extensions rather than members because
// `LingaraClient.kt` is at its function budget, as the event helpers are (ADR 30.9.26aa); import
// them from `com.getlingara.kotlin.embed`.

/**
 * Mints a player's embed token (scope `embed:mint`, a metered client only). Call it on your server,
 * never on a player's device. The token lives 900 seconds and Lingara never refreshes it: mint again
 * when the player kit asks. Store the result's `subject` beside the player.
 *
 * @throws TransportException `malformed_response` when any of the six answer fields is missing or
 *   mistyped, carrying nothing of the answer
 */
public suspend fun LingaraClient.createEmbedToken(body: EmbedTokenRequest): ApiResponse<MintedToken> {
    val json = LingaraJson.encodeToString(EmbedTokenRequest.serializer(), body)
    val call = Call("POST", "/v1/embed/tokens", json.toByteArray(), "application/json", true)
    val answer =
        try {
            requests.json(call, JsonObject.serializer())
        } catch (e: TransportException) {
            // A decoder's message can quote the body, and so the token: drop the cause.
            throw if (e.kind == TransportKind.MALFORMED_RESPONSE) MintedToken.malformed() else e
        }
    return ApiResponse(MintedToken.of(answer.body), answer.servedVersion)
}

/**
 * Deletes a player and revokes its tokens (scope `embed:mint`), [playerRef] sent as one encoded
 * path segment. It is idempotent: an unknown player is a success too, so K4 retries it safely. It
 * keeps working while embedding is switched off for your client.
 */
public suspend fun LingaraClient.deleteEmbedPlayer(playerRef: String): ApiResponse<Unit> =
    requests.empty(Call("DELETE", "/v1/embed/players/" + encodeSegment(playerRef), null, "application/json", true))

/**
 * Streams an NPC's reply to one line (scope `embed:play`, from a player's embed token or a metered
 * client's own): `delta` and `notice`, ending on `done`. Each turn is billed, so it is sent once
 * with no K4 retries: a `429` or `503` is thrown at once as an `ApiException` carrying its
 * `retryAfter`, and sending the turn again is the caller's choice. `403 embed_needs_metered` and
 * `422 safety_input_flagged` ("say something else") are never worth retrying.
 *
 * The window is the caller's and is not checked here: at most 12 `history` entries, `line` and each
 * entry at most 500 characters. Send each NPC reply back into `history` cut to its first 500
 * characters.
 */
public suspend fun LingaraClient.sendDialogueTurn(body: DialogueTurnRequest): EventStream<SendDialogueTurnEvent> {
    val route = Streams.SEND_DIALOGUE_TURN
    val json = LingaraJson.encodeToString(DialogueTurnRequest.serializer(), body)
    val call = Call(route.method, route.path, json.toByteArray(), "text/event-stream", true)
    call.once = true
    return requests.open(route, call)
}
