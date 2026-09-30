package com.getlingara.kotlin

import com.getlingara.kotlin.internal.Streams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Holds `Streams`' generated terminal table to the view both ways (ADR 29.9.26s AC17, ADR
 * 29.9.26ai): every view stream has a route and a client method, every route is a view stream,
 * each route's events and endsOn are the view's, and every terminal is among its events.
 */
object TerminalTable {
    fun assertIsTheView() {
        val view = Json.parseToJsonElement(File(System.getProperty("lingara.view")).readText()).jsonObject
        val streams = view.getValue("x-lingara-streams").jsonArray.map { it.jsonObject }
        val methods =
            LingaraClient::class.java.methods
                .map { it.name }
                .toSet()
        for (entry in streams) {
            val id = entry.text("operationId")
            val route = assertNotNull(Streams.ROUTES[id], id)
            assertEquals(entry.texts("events"), route.events, id)
            assertEquals(entry.texts("endsOn").toSet(), route.endsOn.keys, id)
            assertTrue(route.events.containsAll(route.endsOn.keys), id)
            assertEquals(Streams.Ending.RAISE, route.endsOn[entry.text("error")], id)
            assertTrue(id in methods, "LingaraClient has no $id")
        }
        assertEquals(streams.map { it.text("operationId") }.toSet(), Streams.ROUTES.keys)
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.texts(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }
}
