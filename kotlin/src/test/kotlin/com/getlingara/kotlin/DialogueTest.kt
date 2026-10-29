package com.getlingara.kotlin

import com.getlingara.kotlin.dialogue.DialogueFilters
import com.getlingara.kotlin.dialogue.ExportDialogueOptions
import com.getlingara.kotlin.dialogue.ExportedDialogue
import com.getlingara.kotlin.dialogue.dialogues
import com.getlingara.kotlin.model.RenameDialogueRequest
import com.getlingara.kotlin.model.SaveDialogueDraftRequest
import com.sun.net.httpserver.HttpExchange
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

class DialogueTest {
    private val definition =
        """{"schema":"dialogue.v1","id":"keepers","start":"greet","npcs":[],"checkpoints":[],"facts":[],""" +
            """"x-layout":{"greet":{"x":120,"y":40}}}"""

    // Canonical bytes, deliberately not how kotlinx would re-encode them (spacing, key order).
    private val irJson = """{"checkpoints":[], "id":"keepers","schema":"dialogue.v1","start":"greet"}"""

    private val etag = "\"3b7e3b7e\""

    private fun dialogue(code: String = "checkpoint_unreachable"): String =
        """{"id":"d-1","name":"Night market","created_at":"2026-10-28T09:00:00Z","updated_at":"2026-10-28T09:00:00Z",""" +
            """"draft":{"revision":1,"draft_version":4,"definition":$definition},"active":null,""" +
            """"warnings":[{"code":"$code","path":"/checkpoints/0"}]}"""

    private fun page(
        id: String,
        next: String?,
        more: Boolean,
    ): String {
        val cursor = next?.let { "\"$it\"" } ?: "null"
        val item =
            """{"id":"$id","name":"Night market","ir_id":"keepers","created_at":"2026-10-28T09:00:00Z",""" +
                """"updated_at":"2026-10-28T09:00:00Z","has_draft":true,"active_revision":null,"npc_count":1,"checkpoint_count":2}"""
        return """{"items":[$item],"next_cursor":$cursor,"has_more":$more}"""
    }

    private fun exported(status: Int): (HttpExchange) -> Unit =
        { exchange ->
            exchange.responseHeaders.set("ETag", etag)
            exchange.responseHeaders.set("Lingara-Dialogue-Revision", "3")
            Fakes.respond(exchange, status, "application/json", if (status == 200) irJson else "")
        }

    private fun server(queries: MutableList<String?>): FakeServer {
        val exports = Fakes.script(exported(200), exported(304))
        val pages = Fakes.script({ Fakes.json(it, 200, page("d-1", "dl1", true)) }, { Fakes.json(it, 200, page("d-2", null, false)) })
        return FakeServer()
            .on("/oauth/token", Fakes.token("lgr_at_dialogue", 3600))
            .on("/v1/dialogues/") {
                if (it.requestURI.path.endsWith("/export")) {
                    queries.add(it.requestURI.rawQuery)
                    exports(it)
                } else {
                    Fakes.json(it, 200, dialogue())
                }
            }.on("/v1/dialogues") {
                queries.add(it.requestURI.rawQuery)
                pages(it)
            }
    }

    private fun client(server: FakeServer): LingaraClient =
        LingaraClient {
            baseUrl = server.uri()
            tokenUrl = server.uri("/oauth/token")
            clientCredentials("lgr_cid_test", "lgr_cs_test")
        }

    /**
     * 29.10.26c AC20: through `client.dialogues`, PUT and PATCH reach the server with their JSON
     * bodies and `Content-Type: application/json`; the export answers `Body` with the bytes unparsed
     * and both headers, then `NotModified` on a `304` with `If-None-Match` sent as given; and
     * `pageDialogues` ends on `has_more: false` with its filters resent. The group is an extension,
     * so `LingaraClient.kt` keeps its 15 functions.
     */
    @Test
    fun putPatchExportAndPaging(): Unit =
        runBlocking {
            val queries = mutableListOf<String?>()
            server(queries).use { server ->
                val dialogues = client(server).dialogues
                val draft = dialogues.getDialogue("d-1").body.draft!!
                val save = SaveDialogueDraftRequest(definition = draft.definition, draftVersion = draft.draftVersion)
                dialogues.saveDialogueDraft("d-1", save)
                dialogues.renameDialogue("d-1", RenameDialogueRequest(name = "Renamed"))
                val body = dialogues.exportDialogue("d-1").body
                assertEquals(ExportedDialogue.Body(3, etag, irJson), body)
                val again = dialogues.exportDialogue("d-1", ExportDialogueOptions(revision = "draft", layout = "omit", ifNoneMatch = etag))
                assertEquals(ExportedDialogue.NotModified(3, etag), again.body)
                val ids = dialogues.pageDialogues(DialogueFilters(limit = 1, name = "market")).map { it.id }.toList()
                assertEquals(listOf("d-1", "d-2"), ids)
                val sent = server.seen.filter { it.path.startsWith("/v1/") }
                assertSent(sent)
                assertEquals(null, sent[3].header("if-none-match"))
                assertEquals(etag, sent[4].header("if-none-match"))
                assertEquals(listOf(null, "revision=draft&layout=omit", "limit=1&name=market", "cursor=dl1&limit=1&name=market"), queries)
            }
            assertTrue(LingaraClient::class.java.declaredMethods.none { "ialogues" in it.name })
        }

    /**
     * 29.10.26c D4: the generated `DialogueWarningCode` is a closed enum, so a warning code a later
     * spec version adds fails to decode a `Dialogue` (as `malformed_response`) until the library is
     * re-synced. The README says so; this pins it.
     */
    @Test
    fun anUnknownWarningCodeFailsToDecode(): Unit =
        runBlocking {
            FakeServer()
                .on("/oauth/token", Fakes.token("lgr_at_dialogue", 3600))
                .on("/v1/dialogues/") { Fakes.json(it, 200, dialogue("a_code_from_later")) }
                .use { server ->
                    val failed = assertFailsWith<TransportException> { client(server).dialogues.getDialogue("d-1") }
                    assertEquals(TransportKind.MALFORMED_RESPONSE, failed.kind)
                }
        }

    private fun assertSent(sent: List<Seen>) {
        val (put, patch) = sent[1] to sent[2]
        assertEquals("PUT" to "/v1/dialogues/d-1/draft", put.method to put.path)
        val saved = Json.parseToJsonElement(put.body).jsonObject
        assertEquals(Json.parseToJsonElement(definition), saved["definition"])
        assertEquals("4", saved["draft_version"]!!.jsonPrimitive.content)
        assertEquals("PATCH" to "/v1/dialogues/d-1", patch.method to patch.path)
        assertEquals(Json.parseToJsonElement("""{"name":"Renamed"}""") as JsonObject, Json.parseToJsonElement(patch.body))
        listOf(put, patch).forEach { assertEquals("application/json", it.header("content-type")) }
        assertEquals("GET" to "/v1/dialogues/d-1/export", sent[3].method to sent[3].path)
    }
}
