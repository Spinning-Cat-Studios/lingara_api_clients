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
import com.getlingara.kotlin.model.EmbedPlayerLink
import com.getlingara.kotlin.model.EmbedTokenRequest
import com.getlingara.kotlin.model.SendDialogueTurnEvent
import kotlinx.serialization.json.JsonObject

// The embedding operations (ADR 1.10.26w, 22.10.26t) are extensions rather than members because
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
 * Links a player to the Lingara account that granted your app access (scope `embed:play`; ADR
 * 22.10.26t), [playerRef] sent as one encoded path segment. Call it on a client whose token source
 * returns the player's delegated token, from that account's own consent
 * (`LingaraClientBuilder.tokenSource`): a client-credentials client is refused
 * `403 embed_link_needs_grant`. The library does not obtain that token yet. A repeat answers
 * `existing` and moves what could not move before, so K4 retries it safely: call again while `kept`
 * has `lesson_plans`.
 */
public suspend fun LingaraClient.linkEmbedPlayer(playerRef: String): ApiResponse<EmbedPlayerLink> =
    requests.json(Call("PUT", linkPath(playerRef), null, "application/json", true), EmbedPlayerLink.serializer())

/**
 * Ends a player's link (scope `embed:mint`, on your ordinary client). No live link is
 * `404 embed_link_not_found`, which after a retried success means it was already unlinked.
 */
public suspend fun LingaraClient.unlinkEmbedPlayer(playerRef: String): ApiResponse<Unit> =
    requests.empty(Call("DELETE", linkPath(playerRef), null, "application/json", true))

private fun linkPath(playerRef: String): String = "/v1/embed/players/" + encodeSegment(playerRef) + "/link"

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
