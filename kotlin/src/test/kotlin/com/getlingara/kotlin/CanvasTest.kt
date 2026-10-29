package com.getlingara.kotlin

import com.getlingara.kotlin.canvas.CanvasFilters
import com.getlingara.kotlin.canvas.canvases
import com.getlingara.kotlin.model.RenameCanvasRequest
import com.getlingara.kotlin.model.SaveCanvasDraftRequest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CanvasTest {
    private val definition =
        """{"schema":2,"nodes":[{"id":"n1","kind":"client","position":[0.5,1.5],""" +
            """"fields":{"name":"Lesson server","x_editor_hint":{"collapsed":true}}}]}"""

    private val canvas =
        """{"id":"c-1","name":"Hooks","created_at":"2026-10-20T09:00:00Z","updated_at":"2026-10-20T09:00:00Z",""" +
            """"draft":{"revision":1,"draft_version":4,"definition":$definition,"objects":{}},"active":null}"""

    private fun page(
        id: String,
        next: String?,
        more: Boolean,
    ): String {
        val cursor = next?.let { "\"$it\"" } ?: "null"
        val item =
            """{"id":"$id","name":"Hooks","recipe":"blank","created_at":"2026-10-20T09:00:00Z",""" +
                """"updated_at":"2026-10-20T09:00:00Z","has_draft":true,"active_revision":null,"node_count":1,""" +
                """"node_kinds":["client"],"scopes":[],"objects":[]}"""
        return """{"items":[$item],"total":null,"next_cursor":$cursor,"has_more":$more}"""
    }

    private fun server(queries: MutableList<String?>): FakeServer {
        val pages =
            Fakes.script(
                { Fakes.json(it, 200, page("c-1", "cv1", true)) },
                { Fakes.json(it, 200, page("c-2", null, false)) },
                { Fakes.json(it, 200, page("c-3", null, true)) },
            )
        return FakeServer()
            .on("/oauth/token", Fakes.token("lgr_at_canvas", 3600))
            .on("/v1/canvases/") { Fakes.json(it, 200, canvas) }
            .on("/v1/canvases") {
                queries.add(it.requestURI.rawQuery)
                pages(it)
            }
    }

    /**
     * 16.10.26ak AC13: through `client.canvases`, PUT and PATCH reach the server with their JSON
     * bodies and `Content-Type: application/json`; a definition read then saved keeps an unknown
     * `fields` key; `pageCanvases` ends on `has_more: false` with its filters resent, and throws
     * `malformed_response` on a page with `has_more` but no cursor; and the group is an extension,
     * so `LingaraClient.kt` keeps its 15 functions.
     */
    @Test
    fun putPatchAndPaging(): Unit =
        runBlocking {
            val queries = mutableListOf<String?>()
            server(queries).use { server ->
                val canvases =
                    LingaraClient {
                        baseUrl = server.uri()
                        tokenUrl = server.uri("/oauth/token")
                        clientCredentials("lgr_cid_test", "lgr_cs_test")
                    }.canvases
                val draft = canvases.getCanvas("c-1").body.draft!!
                canvases.saveCanvasDraft("c-1", SaveCanvasDraftRequest(definition = draft.definition, draftVersion = draft.draftVersion))
                canvases.renameCanvas("c-1", RenameCanvasRequest(name = "Renamed"))
                val ids = canvases.pageCanvases(CanvasFilters(limit = 1, kinds = listOf("client", "webhook"))).map { it.id }.toList()
                assertEquals(listOf("c-1", "c-2"), ids)
                val malformed = assertFailsWith<TransportException> { canvases.pageCanvases().toList() }
                assertEquals(TransportKind.MALFORMED_RESPONSE, malformed.kind)
                assertSent(server.seen.filter { it.path.startsWith("/v1/") })
                assertEquals(listOf("limit=1&kinds=client%2Cwebhook", "cursor=cv1&limit=1&kinds=client%2Cwebhook"), queries.take(2))
                assertEquals(3, queries.size)
            }
            assertTrue(LingaraClient::class.java.declaredMethods.none { "anvas" in it.name })
        }

    private fun assertSent(sent: List<Seen>) {
        val (put, patch) = sent[1] to sent[2]
        assertEquals("PUT" to "/v1/canvases/c-1/draft", put.method to put.path)
        val saved = Json.parseToJsonElement(put.body).jsonObject
        assertEquals(Json.parseToJsonElement(definition), saved["definition"])
        assertEquals("4", saved["draft_version"]!!.jsonPrimitive.content)
        assertEquals("PATCH" to "/v1/canvases/c-1", patch.method to patch.path)
        assertEquals(Json.parseToJsonElement("""{"name":"Renamed"}""") as JsonObject, Json.parseToJsonElement(patch.body))
        listOf(put, patch).forEach { assertEquals("application/json", it.header("content-type")) }
    }
}
