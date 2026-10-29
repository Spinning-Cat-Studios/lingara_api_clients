package com.getlingara.kotlin.dialogue

import com.getlingara.kotlin.ApiResponse
import com.getlingara.kotlin.TransportException
import com.getlingara.kotlin.TransportKind
import com.getlingara.kotlin.internal.Call
import com.getlingara.kotlin.internal.LingaraJson
import com.getlingara.kotlin.internal.Requests
import com.getlingara.kotlin.internal.encodeSegment
import com.getlingara.kotlin.model.ActivateDialogueRequest
import com.getlingara.kotlin.model.CreateDialogueRequest
import com.getlingara.kotlin.model.Dialogue
import com.getlingara.kotlin.model.DialoguePage
import com.getlingara.kotlin.model.DialogueSummary
import com.getlingara.kotlin.model.RenameDialogueRequest
import com.getlingara.kotlin.model.SaveDialogueDraftRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer

/**
 * What [Dialogues.listDialogues] filters by, and what [Dialogues.pageDialogues] resends on every
 * page (ADR 29.10.26c D5).
 *
 * @property limit the page size, 1 to 50
 * @property name a substring of the dialogue name
 */
public data class DialogueFilters(
    val limit: Int? = null,
    val name: String? = null,
)

/**
 * The stored-dialogue operations (scopes `dialogues:read`, `dialogues:write`; ADR 29.10.26c),
 * reached as `client.dialogues`. The group holds no state of its own: every call goes through the
 * client's request path, so the token, retries, version pin and errors are the client's.
 *
 * A stale `draft_version` is `409 dialogue_conflict`, thrown as an `ApiException` and never
 * retried: re-read with [getDialogue], take `draft.draftVersion` (or send `0` when `draft` is
 * `null`), and decide. Branch on an error's `code`, never its localised message. The export is
 * read by hand: its body is canonical bytes never parsed here, its two headers are part of the
 * answer, and a `304` is an answer. There is no delete: a dialogue is deleted through the
 * platform's lifecycle routes, not this library.
 */
public class Dialogues internal constructor(
    private val requests: Requests,
) {
    /** One page of dialogues from [cursor] (a previous page's `next_cursor`); [pageDialogues] walks them. */
    public suspend fun listDialogues(
        filters: DialogueFilters = DialogueFilters(),
        cursor: String? = null,
    ): ApiResponse<DialoguePage> = requests.get("/v1/dialogues" + listQuery(filters, cursor), true, DialoguePage.serializer())

    /** Creates a dialogue (`201`). Its `warnings` are advice, never a refusal. */
    public suspend fun createDialogue(body: CreateDialogueRequest): ApiResponse<Dialogue> =
        send("POST", "/v1/dialogues", body, CreateDialogueRequest.serializer())

    /** One dialogue, [dialogueId] sent as one encoded path segment, its draft and active revision inline. */
    public suspend fun getDialogue(dialogueId: String): ApiResponse<Dialogue> =
        requests.get(dialoguePath(dialogueId), true, Dialogue.serializer())

    /** Renames a dialogue (`PATCH`). */
    public suspend fun renameDialogue(
        dialogueId: String,
        body: RenameDialogueRequest,
    ): ApiResponse<Dialogue> = send("PATCH", dialoguePath(dialogueId), body, RenameDialogueRequest.serializer())

    /**
     * Saves the draft (`PUT`); `draftVersion = 0` with no draft opens one. A stale `draft_version`
     * is `409 dialogue_conflict`, which may follow your own retried save: re-read with
     * [getDialogue] and compare before you decide.
     */
    public suspend fun saveDialogueDraft(
        dialogueId: String,
        body: SaveDialogueDraftRequest,
    ): ApiResponse<Dialogue> = send("PUT", dialoguePath(dialogueId) + "/draft", body, SaveDialogueDraftRequest.serializer())

    /** Promotes the draft. A stale or missing draft is `409 dialogue_conflict` and writes nothing. */
    public suspend fun activateDialogue(
        dialogueId: String,
        body: ActivateDialogueRequest,
    ): ApiResponse<Dialogue> = send("POST", dialoguePath(dialogueId) + "/activate", body, ActivateDialogueRequest.serializer())

    /**
     * Exports a dialogue (`dialogues:read`, which an embed token carries for the active revision).
     * Pass the `etag` you hold as [ExportDialogueOptions.ifNoneMatch], and an unchanged revision
     * answers [ExportedDialogue.NotModified] with no body; a `304` still spends one unit of the rate
     * budget. A missing `ETag`, or a missing or non-integer `Lingara-Dialogue-Revision`, is
     * `TransportException` `malformed_response`. K4's retries apply: the export only reads.
     */
    public suspend fun exportDialogue(
        dialogueId: String,
        options: ExportDialogueOptions = ExportDialogueOptions(),
    ): ApiResponse<ExportedDialogue> = export(requests, dialoguePath(dialogueId), options)

    /**
     * Every dialogue the filters match, following `next_cursor` until a page says `has_more: false`,
     * the filters resent on every page. Each collection fetches from the first page; it never sleeps
     * or polls and ends on the first error. A page with `has_more` and no `next_cursor` is
     * `TransportException` `malformed_response`, its items unemitted.
     */
    public fun pageDialogues(filters: DialogueFilters = DialogueFilters()): Flow<DialogueSummary> =
        flow {
            var cursor: String? = null
            do {
                val page = listDialogues(filters, cursor).body
                if (page.hasMore && page.nextCursor == null) {
                    throw TransportException(TransportKind.MALFORMED_RESPONSE, null)
                }
                page.items.forEach { emit(it) }
                cursor = page.nextCursor
            } while (page.hasMore)
        }

    private suspend fun <B> send(
        method: String,
        path: String,
        body: B,
        serializer: KSerializer<B>,
    ): ApiResponse<Dialogue> {
        val json = LingaraJson.encodeToString(serializer, body)
        return requests.json(Call(method, path, json.toByteArray(), "application/json", true), Dialogue.serializer())
    }
}

private fun dialoguePath(dialogueId: String): String = "/v1/dialogues/" + encodeSegment(dialogueId)

/** The values given, each encoded, in the order given; `""` when none is. */
internal fun query(vararg pairs: Pair<String, String?>): String {
    val present = pairs.mapNotNull { (key, value) -> value?.let { key + "=" + encodeSegment(it) } }
    return if (present.isEmpty()) "" else "?" + present.joinToString("&")
}

private fun listQuery(
    filters: DialogueFilters,
    cursor: String?,
): String = query("cursor" to cursor, "limit" to filters.limit?.toString(), "name" to filters.name)
