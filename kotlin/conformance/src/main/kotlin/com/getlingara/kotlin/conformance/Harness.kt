package com.getlingara.kotlin.conformance

import com.getlingara.kotlin.LingaraClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/** The harness's JSON: every model field written, so a comparison sees what the server sent. */
internal val HarnessJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

/** What `conformance-server run` passes. */
internal class Env(
    val base: String,
    val token: String,
    val control: String,
    val out: Path,
    val only: Set<String>,
)

/**
 * The Kotlin library's conformance harness (conformance/README.md, Writing a harness; ADR 29.9.26s
 * D11). It runs every case through the library's public API: each client is built from the case's
 * `client` block through the DSL only ([Rig]), a `parallel: n` step is n coroutines released
 * together, and `cancel_after_events: n` cancels the collecting Job after its n-th event
 * ([Observe]). This `main` is the one `runBlocking` in the Kotlin sources, and it is not library
 * API.
 */
fun main() {
    val passed = runBlocking { Harness(readEnv()).run() }
    exitProcess(if (passed) 0 else 1)
}

private fun readEnv(): Env {
    val only = System.getenv("LINGARA_CONFORMANCE_ONLY").orEmpty()
    return Env(
        required("LINGARA_CONFORMANCE_BASE_URL"),
        required("LINGARA_CONFORMANCE_TOKEN_URL"),
        required("LINGARA_CONFORMANCE_CONTROL_URL"),
        Path.of(required("LINGARA_CONFORMANCE_OUT")),
        only
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet(),
    )
}

private fun required(name: String): String =
    System.getenv(name)?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("$name is not set: run this through conformance-server run")

private class Harness(
    private val env: Env,
) {
    private val control = HttpClient.newHttpClient()

    suspend fun run(): Boolean {
        var all = true
        for (id in call("GET", "/cases").jsonArray.map { it.jsonPrimitive.content }) {
            if (env.only.isEmpty() || id in env.only) all = runCase(id) && all
        }
        return all
    }

    private suspend fun runCase(id: String): Boolean {
        val started = System.nanoTime()
        val case = call("GET", "/cases/$id").jsonObject
        call("POST", "/cases/$id/arm")
        val client =
            try {
                steps(case)
            } catch (e: Exception) {
                listOf("harness: $e")
            }
        val verdict = call("POST", "/cases/$id/finish").jsonObject
        return report(id, started, client, verdict["mismatches"] as? JsonArray ?: JsonArray(emptyList()))
    }

    private suspend fun steps(case: JsonObject): List<String> {
        val rig = Rig.build(case["client"] as? JsonObject ?: JsonObject(emptyMap()), env.base, env.token)
        val mismatches = mutableListOf<String>()
        for (step in (case["steps"] as? JsonArray).orEmpty().map { it.jsonObject }) {
            step["advance_clock_s"]?.let { rig.advance(it.jsonPrimitive.content.toLong()) }
            val expect = Compare.substitute(step["expect"] ?: continue, env.base).jsonObject
            (step["call"] as? JsonObject)?.let { mismatches += Observe.runStep(rig, it, expect) }
            for (kind in listOf("events", "tail")) {
                (step[kind] as? JsonObject)?.let { mismatches += EventSteps.runStep(rig, kind, it, expect) }
            }
        }
        return mismatches
    }

    private fun report(
        id: String,
        started: Long,
        client: List<String>,
        server: JsonArray,
    ): Boolean {
        val pass = client.isEmpty() && server.isEmpty()
        val line =
            buildJsonObject {
                put("case", id)
                put("lang", "kotlin")
                put("library_version", LingaraClient.LIBRARY_VERSION)
                put("result", if (pass) "pass" else "fail")
                put("client_mismatches", JsonArray(client.map { JsonPrimitive(it) }))
                put("server_mismatches", server)
                put("duration_ms", (System.nanoTime() - started) / 1_000_000)
            }
        Files.writeString(env.out, "$line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        if (!pass) System.err.println("✗ $id: client $client server $server")
        return pass
    }

    /** Calls the control surface and decodes its JSON answer. */
    private fun call(
        method: String,
        path: String,
    ): JsonElement {
        val request =
            HttpRequest
                .newBuilder(URI.create(env.control + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
        val response = control.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IOException("$path: ${response.statusCode()} ${response.body()}")
        return HarnessJson.parseToJsonElement(response.body())
    }
}
