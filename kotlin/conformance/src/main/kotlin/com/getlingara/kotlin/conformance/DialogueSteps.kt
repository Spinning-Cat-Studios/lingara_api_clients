package com.getlingara.kotlin.conformance

import com.getlingara.kotlin.ApiResponse
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.dialogue.DialogueFilters
import com.getlingara.kotlin.dialogue.ExportDialogueOptions
import com.getlingara.kotlin.dialogue.ExportedDialogue
import com.getlingara.kotlin.dialogue.dialogues
import com.getlingara.kotlin.model.ActivateDialogueRequest
import com.getlingara.kotlin.model.CreateDialogueRequest
import com.getlingara.kotlin.model.Dialogue
import com.getlingara.kotlin.model.DialoguePage
import com.getlingara.kotlin.model.DialogueSummary
import com.getlingara.kotlin.model.RenameDialogueRequest
import com.getlingara.kotlin.model.SaveDialogueDraftRequest
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * The dialogue group's steps (ADR 29.10.26c D7): the seven operations, each id from
 * `params.dialogue_id`, and the `page_dialogues` step. An export is reported under `body` as
 * `{revision, etag, ir_json}`, or `{not_modified: true, revision, etag}` for a `304`.
 */
internal object DialogueSteps {
    /** A `page_dialogues` step: every item, as one list for `body`. */
    suspend fun page(
        c: LingaraClient,
        params: JsonObject?,
    ): JsonArray =
        JsonArray(
            c.dialogues.pageDialogues(filters(params)).toList().map {
                HarnessJson.encodeToJsonElement(DialogueSummary.serializer(), it)
            },
        )

    private fun filters(params: JsonObject?): DialogueFilters = DialogueFilters(params?.number("limit")?.toInt(), params?.text("name"))

    /** One of the seven dialogue operations; `null` for any other. */
    suspend fun call(
        c: LingaraClient,
        call: JsonObject,
        operation: String,
    ): Seen? {
        val dialogues = c.dialogues
        val params = call["params"] as? JsonObject
        val id = params?.text("dialogue_id").orEmpty()
        val one = Dialogue.serializer()
        return when (operation) {
            "listDialogues" ->
                Observe.result(DialoguePage.serializer()) { dialogues.listDialogues(filters(params), params?.text("cursor")) }
            "createDialogue" -> Observe.result(one) { dialogues.createDialogue(Observe.body(call, CreateDialogueRequest.serializer())) }
            "getDialogue" -> Observe.result(one) { dialogues.getDialogue(id) }
            "renameDialogue" -> Observe.result(one) { dialogues.renameDialogue(id, Observe.body(call, RenameDialogueRequest.serializer())) }
            "saveDialogueDraft" ->
                Observe.result(one) { dialogues.saveDialogueDraft(id, Observe.body(call, SaveDialogueDraftRequest.serializer())) }
            "activateDialogue" ->
                Observe.result(one) { dialogues.activateDialogue(id, Observe.body(call, ActivateDialogueRequest.serializer())) }
            "exportDialogue" -> export { dialogues.exportDialogue(id, options(params)) }
            else -> null
        }
    }

    private fun options(params: JsonObject?): ExportDialogueOptions =
        ExportDialogueOptions(params?.text("revision"), params?.text("layout"), params?.text("if_none_match"))

    /** The export's result as the case reads it, its status `200` or `304`. */
    private suspend fun export(call: suspend () -> ApiResponse<ExportedDialogue>): Seen {
        val response =
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Observe.failed(e, emptyList(), null)
            }
        val result = response.body
        return Seen().apply {
            outcome = "completed"
            status = if (result is ExportedDialogue.NotModified) 304 else 200
            servedVersion = response.servedVersion
            body =
                buildJsonObject {
                    if (result is ExportedDialogue.NotModified) put("not_modified", true)
                    put("revision", result.revision)
                    put("etag", result.etag)
                    if (result is ExportedDialogue.Body) put("ir_json", result.irJson)
                }
            renderings += listOf(response.toString(), result.toString())
        }
    }
}
