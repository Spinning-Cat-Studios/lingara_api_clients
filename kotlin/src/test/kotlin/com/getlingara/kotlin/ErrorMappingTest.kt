package com.getlingara.kotlin

import com.getlingara.kotlin.model.VocabRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ErrorMappingTest {
    private fun credentialed(server: FakeServer): LingaraClient =
        LingaraClient {
            baseUrl = server.uri()
            tokenUrl = server.uri("/oauth/token")
            clientCredentials("lgr_cid_test", "lgr_cs_test")
        }

    /**
     * 29.9.26s AC11: a plain-text 503 from either endpoint is MaintenanceException; a non-envelope
     * /v1 502 is ApiException with code http_502; a non-RFC 6749 token-endpoint 500 is
     * OAuthException with error http_500; a /v1 200 missing a required field is TransportException
     * MALFORMED_RESPONSE, and one carrying an extra field decodes.
     */
    @Test
    fun responsesMapToTheErrorFamily() =
        runBlocking {
            val maintenance: Handler = { Fakes.respond(it, 503, "text/plain; charset=utf-8", MAINTENANCE) }
            val badGateway: Handler = { Fakes.respond(it, 502, "text/html", "<html>bad gateway</html>") }
            val versions = Fakes.script(Fakes.status(200, null, null, "{}"), Fakes.status(200, null, null, EXTRA))
            val server =
                FakeServer()
                    .on("/v1/usage", maintenance)
                    .on("/v1/lesson-plans/", badGateway)
                    .on("/v1/versions", versions)
                    .on("/oauth/token", maintenance)
            server.use {
                val free = LingaraClient { baseUrl = server.uri() }
                assertEquals(MAINTENANCE, assertFailsWith<MaintenanceException> { free.getUsage() }.body)
                val proxy = assertFailsWith<ApiException> { free.getLessonPlan("p") }
                assertEquals(502, proxy.status)
                assertEquals("http_502", proxy.code)
                assertEquals(MAINTENANCE, assertFailsWith<MaintenanceException> { credentialed(server).getUsage() }.body)
                val missing = assertFailsWith<TransportException> { free.listApiVersions() }
                assertEquals(TransportKind.MALFORMED_RESPONSE, missing.kind)
                assertEquals(listOf(), free.listApiVersions().body.versions)
            }
            FakeServer().on("/oauth/token") { Fakes.respond(it, 500, null, "") }.use { tokenServer ->
                val e = assertFailsWith<OAuthException> { credentialed(tokenServer).getUsage() }
                assertEquals(500, e.status)
                assertEquals("http_500", e.error)
                assertNull(e.description)
            }
        }

    private suspend fun kindOf(client: LingaraClient): TransportKind = assertFailsWith<TransportException> { client.listApiVersions() }.kind

    private fun at(base: URI): LingaraClient = LingaraClient { baseUrl = base }

    /**
     * 29.9.26s AC12: garbage answering a ClientHello is TLS; a refused port, a stub HttpClient
     * failing with HttpConnectTimeoutException and a listener that closes before the status line
     * are CONNECT; a body cut mid-read is RESET, on a JSON call and on a stream alike.
     */
    @Test
    fun transportFailuresMapToTheirKinds() =
        runBlocking {
            Listener { Listener.write(it, "this is not TLS at all\r\n\r\n") }.use { garbage ->
                assertEquals(TransportKind.TLS, kindOf(at(garbage.uri("https"))))
            }
            val refused = Listener {}.use { it.uri("http") }
            assertEquals(TransportKind.CONNECT, kindOf(at(refused)))
            val stub =
                LingaraClient {
                    baseUrl = refused
                    httpClient = ConnectTimeoutClient()
                }
            assertEquals(TransportKind.CONNECT, kindOf(stub))
            Listener(Listener::readRequest).use { assertEquals(TransportKind.CONNECT, kindOf(at(it.uri("http")))) }
            cut("application/json", "{\"versions\":[").use { assertEquals(TransportKind.RESET, kindOf(at(it.uri("http")))) }
            cut("text/event-stream", "event: started\ndata: {").use { listener ->
                val stream = at(listener.uri("http")).generateVocabulary(VocabRequest(2, "en", "zh"))
                val e = assertFailsWith<TransportException> { stream.collect {} }
                assertEquals(TransportKind.RESET, e.kind)
            }
        }

    /** Promises 100 bytes, sends the head of them, and hangs up. */
    private fun cut(
        contentType: String,
        head: String,
    ): Listener =
        Listener {
            Listener.readRequest(it)
            Listener.write(it, "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: 100\r\n\r\n$head")
        }

    /** A caller's HttpClient whose every send fails as a connect timeout: loopback cannot. */
    private class ConnectTimeoutClient : HttpClient() {
        override fun <T> send(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> = throw HttpConnectTimeoutException("connect timed out")

        override fun <T> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(HttpConnectTimeoutException("connect timed out"))

        override fun <T> sendAsync(
            request: HttpRequest,
            handler: HttpResponse.BodyHandler<T>,
            push: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = sendAsync(request, handler)

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.empty()

        override fun followRedirects(): Redirect = Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): Version = Version.HTTP_1_1

        override fun executor(): Optional<Executor> = Optional.empty()
    }

    private companion object {
        const val MAINTENANCE = "Service is under maintenance. Please try again later."
        const val EXTRA = """{"current":null,"versions":[],"added_later":{"a":1}}"""
    }
}
