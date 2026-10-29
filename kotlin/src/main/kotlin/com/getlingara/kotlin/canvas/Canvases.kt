package com.getlingara.kotlin.canvas

import com.getlingara.kotlin.ApiResponse
import com.getlingara.kotlin.TransportException
import com.getlingara.kotlin.TransportKind
import com.getlingara.kotlin.internal.Call
import com.getlingara.kotlin.internal.LingaraJson
import com.getlingara.kotlin.internal.Requests
import com.getlingara.kotlin.internal.encodeSegment
import com.getlingara.kotlin.model.ActivateCanvasRequest
import com.getlingara.kotlin.model.Canvas
import com.getlingara.kotlin.model.CanvasDryRun
import com.getlingara.kotlin.model.CanvasPage
import com.getlingara.kotlin.model.CanvasPlan
import com.getlingara.kotlin.model.CanvasSummary
import com.getlingara.kotlin.model.CreateCanvasRequest
import com.getlingara.kotlin.model.RenameCanvasRequest
import com.getlingara.kotlin.model.SaveCanvasDraftRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer

/**
 * What [Canvases.listCanvases] filters by, and what [Canvases.pageCanvases] resends on every page
 * (ADR 16.10.26ak D5). Each list is sent comma-separated as one value; an empty one is left out.
 *
 * @property limit the page size, 1 to 50
 * @property name a substring of the canvas name
 * @property status `all`, `live`, `changed` or `unapplied`
 */
public data class CanvasFilters(
    val limit: Int? = null,
    val name: String? = null,
    val status: String? = null,
    val recipes: List<String> = emptyList(),
    val kinds: List<String> = emptyList(),
    val scopes: List<String> = emptyList(),
)

/**
 * The canvas operations (scopes `canvas:read`, `canvas:write`; ADR 16.10.26ak), reached as
 * `client.canvases`. The group holds no state of its own: every call goes through the client's
 * request path, so the token, retries, version pin and errors are the client's.
 *
 * A stale `draft_version` is `409 canvas_conflict`, thrown as an `ApiException` and never retried:
 * re-read with [getCanvas], compare drafts, and decide. Branch on an error's `code`, never its
 * localised message. A dry-run answers `200` whether or not `ok` is true; a refused verdict's `code`
 * and English `error` are data. There is no delete: a canvas is deleted through the platform's
 * lifecycle routes, not this library.
 */
public class Canvases internal constructor(
    private val requests: Requests,
) {
    /** One page of canvases from [cursor] (a previous page's `next_cursor`); [pageCanvases] walks them. */
    public suspend fun listCanvases(
        filters: CanvasFilters = CanvasFilters(),
        cursor: String? = null,
    ): ApiResponse<CanvasPage> = requests.get("/v1/canvases" + canvasQuery(filters, cursor), true, CanvasPage.serializer())

    /** Creates a canvas (`201`). The answer does not echo `recipe`. */
    public suspend fun createCanvas(body: CreateCanvasRequest): ApiResponse<Canvas> =
        send("POST", "/v1/canvases", body, CreateCanvasRequest.serializer())

    /** One canvas, [canvasId] sent as one encoded path segment, its draft and active revision inline. */
    public suspend fun getCanvas(canvasId: String): ApiResponse<Canvas> = requests.get(path(canvasId), true, Canvas.serializer())

    /** Renames a canvas (`PATCH`). */
    public suspend fun renameCanvas(
        canvasId: String,
        body: RenameCanvasRequest,
    ): ApiResponse<Canvas> = send("PATCH", path(canvasId), body, RenameCanvasRequest.serializer())

    /**
     * Saves the draft (`PUT`). A stale `draft_version` is `409 canvas_conflict`, which may follow your
     * own retried save: re-read with [getCanvas] and compare before you decide.
     */
    public suspend fun saveCanvasDraft(
        canvasId: String,
        body: SaveCanvasDraftRequest,
    ): ApiResponse<Canvas> = send("PUT", path(canvasId) + "/draft", body, SaveCanvasDraftRequest.serializer())

    /**
     * Promotes a draft with the objects an apply made. A replayed activate at a `draft_version` already
     * promoted is `409 canvas_conflict` and writes nothing.
     */
    public suspend fun activateCanvas(
        canvasId: String,
        body: ActivateCanvasRequest,
    ): ApiResponse<Canvas> = send("POST", path(canvasId) + "/activate", body, ActivateCanvasRequest.serializer())

    /** Judges a plan without applying it. A plan with a refused op still answers `200`, `ok: false`. */
    public suspend fun dryRunCanvasPlan(body: CanvasPlan): ApiResponse<CanvasDryRun> {
        val json = LingaraJson.encodeToString(CanvasPlan.serializer(), body)
        val call = Call("POST", "/v1/canvases/dry-run", json.toByteArray(), "application/json", true)
        return requests.json(call, CanvasDryRun.serializer())
    }

    /**
     * Every canvas the filters match, following `next_cursor` until a page says `has_more: false`, the
     * filters resent on every page. Each collection fetches from the first page; it never sleeps or
     * polls and ends on the first error. A page with `has_more` and no `next_cursor` is
     * `TransportException` `malformed_response`, its items unemitted.
     */
    public fun pageCanvases(filters: CanvasFilters = CanvasFilters()): Flow<CanvasSummary> =
        flow {
            var cursor: String? = null
            do {
                val page = listCanvases(filters, cursor).body
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
    ): ApiResponse<Canvas> {
        val json = LingaraJson.encodeToString(serializer, body)
        return requests.json(Call(method, path, json.toByteArray(), "application/json", true), Canvas.serializer())
    }
}

private fun path(canvasId: String): String = "/v1/canvases/" + encodeSegment(canvasId)

/** The query: each value encoded, each list one comma-separated value. */
internal fun canvasQuery(
    filters: CanvasFilters,
    cursor: String?,
): String {
    val lists = listOf("recipes" to filters.recipes, "kinds" to filters.kinds, "scopes" to filters.scopes)
    val pairs =
        listOf("cursor" to cursor, "limit" to filters.limit?.toString(), "name" to filters.name, "status" to filters.status) +
            lists.map { (key, list) -> key to list.takeIf { it.isNotEmpty() }?.joinToString(",") }
    val present = pairs.mapNotNull { (key, value) -> value?.let { key + "=" + encodeSegment(it) } }
    return if (present.isEmpty()) "" else "?" + present.joinToString("&")
}
