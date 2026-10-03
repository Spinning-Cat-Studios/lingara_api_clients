package com.getlingara.kotlin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LingaraClientTest {
    private fun server(): FakeServer =
        FakeServer()
            .on("/oauth/token", Fakes.token("lgr_at_client", 3600))
            .on("/v1/usage", Fakes.status(200, "Lingara-Version", PIN, """{"allowance":[]}"""))
            .on("/v1/versions", Fakes.status(200, "Lingara-Version", PIN, VERSIONS))

    private fun credentialed(
        server: FakeServer,
        more: LingaraClientBuilder.() -> Unit = {},
    ): LingaraClient =
        LingaraClient {
            baseUrl = server.uri()
            tokenUrl = server.uri("/oauth/token")
            clientCredentials("lgr_cid_test", "lgr_cs_test")
            more()
        }

    /**
     * 29.9.26s AC13: cancelling a JSON call whose server has not answered makes a local listener
     * see the connection close within 2 s, and the caller gets its own CancellationException, not
     * a LingaraException.
     */
    @Test
    fun cancellingAJsonCallAbortsTheExchange(): Unit =
        runBlocking {
            val accepted = CountDownLatch(1)
            val hungUp = CountDownLatch(1)
            val listener =
                Listener {
                    Listener.readRequest(it)
                    accepted.countDown()
                    Listener.awaitHangUp(it)
                    hungUp.countDown()
                }
            listener.use {
                val client = LingaraClient { baseUrl = listener.uri("http") }
                val thrown = CompletableDeferred<Throwable>()
                val call =
                    launch(Dispatchers.Default) {
                        try {
                            client.listApiVersions()
                        } catch (e: Throwable) {
                            thrown.complete(e)
                            throw e
                        }
                    }
                assertTrue(accepted.await(2, TimeUnit.SECONDS))
                call.cancel()
                assertTrue(hungUp.await(2, TimeUnit.SECONDS), "the server saw no disconnect within 2 s")
                assertIs<CancellationException>(withTimeout(2.seconds) { thrown.await() })
            }
        }

    /**
     * 29.9.26s AC20: the User-Agent matches C2 D8's pattern with lang kotlin and a runtime with no
     * ")", and a suffix is appended after it.
     */
    @Test
    fun userAgentLeadsWithTheLibraryToken() =
        runBlocking {
            server().use { server ->
                LingaraClient { baseUrl = server.uri() }.listApiVersions()
                LingaraClient {
                    baseUrl = server.uri()
                    userAgentSuffix = "kanji-quest/2.1"
                }.listApiVersions()
                val plain = server.seen[0].header("user-agent")!!
                val suffixed = server.seen[1].header("user-agent")!!
                assertTrue(K6.matches(plain), plain)
                assertTrue(plain.startsWith("lingara-kotlin/${LingaraClient.LIBRARY_VERSION} (kotlin/"), plain)
                assertTrue(plain.contains("; jvm/"), plain)
                assertTrue(K6.matches(suffixed), suffixed)
                assertEquals("$plain kanji-quest/2.1", suffixed)
            }
        }

    /**
     * 29.9.26s AC21: a pinned client sends Lingara-Version on every /v1 request and never to the
     * token endpoint, and User-Agent goes to both.
     */
    @Test
    fun headersReachTheRightEndpoints() =
        runBlocking {
            server().use { server ->
                val client = credentialed(server) { version = PIN }
                client.getUsage()
                client.listApiVersions()
                for (seen in server.seen) {
                    assertTrue(seen.header("user-agent")!!.startsWith("lingara-kotlin/"), seen.path)
                    if (seen.path == "/oauth/token") {
                        assertNull(seen.header("lingara-version"))
                    } else {
                        assertEquals(PIN, seen.header("lingara-version"), seen.path)
                    }
                }
                assertEquals(3, server.seen.size)
            }
        }

    /**
     * 29.9.26s AC22: a JSON method's ApiResponse carries servedVersion from the echo, and a
     * credential-free client calls listApiVersions and getApiVersion(id) with no exchange and no
     * Authorization header.
     */
    @Test
    fun servedVersionAndTheCredentialFreeClient() =
        runBlocking {
            server().on("/v1/versions/", Fakes.status(200, null, null, detail(PIN))).use { server ->
                val free = LingaraClient { baseUrl = server.uri() }
                assertEquals(PIN, free.listApiVersions().servedVersion)
                assertEquals(PIN, free.getApiVersion(PIN).body.id)
                assertNull(free.getApiVersion(PIN).servedVersion)
                credentialed(server).listApiVersions()
                assertEquals(0, server.hits("/oauth/token"))
                server.seen.forEach { assertNull(it.header("authorization"), it.path) }
                assertEquals("/v1/versions/$PIN", server.seen[1].path)
            }
        }

    /**
     * 29.9.26s AC23: the builder throws IllegalArgumentException for an empty version and for
     * tokenSource beside clientCredentials.
     */
    @Test
    fun builderRefusesConflictingOptions() {
        assertFailsWith<IllegalArgumentException> { LingaraClient { version = "" } }
        val own =
            object : TokenSource {
                override suspend fun token(): AccessToken = AccessToken("lgr_at_own")

                override fun invalidate(token: AccessToken) = Unit
            }
        assertFailsWith<IllegalArgumentException> {
            LingaraClient {
                clientCredentials("id", "secret")
                tokenSource = own
            }
        }
        LingaraClient { tokenSource = own }
    }

    private companion object {
        /** CONTRACT.md K6's pattern, which the conformance server checks on every request. */
        val K6 =
            Regex(
                "^lingara-(typescript|rust|go|java|kotlin|ruby|php)/(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\." +
                    "(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)? \\([\\x20-\\x28\\x2A-\\x7E]+\\)( .+)?$",
            )
        const val VERSIONS = """{"current":null,"versions":[]}"""
        const val PIN = "2026-09-knowing-tenpounder"

        fun detail(id: String): String =
            """{"id":"$id","state":"supported","lts":false,"minted_at":"2026-09-01T00:00:00Z",""" +
                """"sunset_at":null,"summary":null,"history":[],"spec":{"url":"/v1/openapi.json"},""" +
                """"asyncapi":{"url":"/v1/asyncapi.json"}}"""
    }
}
