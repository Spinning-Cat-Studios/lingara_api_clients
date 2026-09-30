package com.getlingara.kotlin

import com.getlingara.kotlin.internal.ErrorMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    /** Every rendering of a throwable and its causes. */
    private fun renderings(failure: Throwable): List<String> =
        generateSequence(failure) { it.cause }.flatMap { sequenceOf(it.toString(), it.message.toString()) }.toList()

    private fun credentialed(server: FakeServer): LingaraClient =
        LingaraClient {
            baseUrl = server.uri()
            tokenUrl = server.uri("/oauth/token")
            clientCredentials("lgr_cid_redact", SECRET)
        }

    /**
     * 29.9.26s AC10: neither the secret nor the token appears in the toString() of ClientSecret,
     * AccessToken, ClientCredentialsTokenSource, LingaraClient or any exception, nor in any
     * exception's message or cause; each rendering that would hold one shows [REDACTED], the
     * client_id is rendered, and exposeSecret() is the only public member that returns a raw value.
     */
    @Test
    fun secretsNeverRender() =
        runBlocking {
            val seen = mutableListOf<String>()
            val forbidden = Fakes.status(403, null, null, """{"code":"forbidden","error":"No."}""")
            FakeServer().on("/oauth/token", Fakes.token(TOKEN, 3600)).on("/v1/usage", forbidden).use { server ->
                val client = credentialed(server)
                seen += renderings(assertFailsWith<ApiException> { client.getUsage() })
                seen += client.toString()
                assertTrue(client.toString().contains("lgr_cid_redact"), "the client id is rendered")
                assertTrue(client.toString().contains("[REDACTED]"))
            }
            val refused = Fakes.status(401, null, null, """{"error":"invalid_client"}""")
            FakeServer().on("/oauth/token", refused).use { server ->
                seen += renderings(assertFailsWith<OAuthException> { credentialed(server).getUsage() })
            }
            val echoed =
                ErrorMapper.transport(IOException("the proxy echoed Basic $SECRET and Bearer $TOKEN"), false, listOf(SECRET, TOKEN))
            seen += renderings(echoed)
            assertEquals(TransportKind.CONNECT, echoed.kind, "scrubbing keeps the kind")
            assertEquals("[REDACTED]", ClientSecret(SECRET).toString())
            assertEquals("[REDACTED]", AccessToken(TOKEN).toString())
            seen.forEach {
                assertFalse(it.contains(SECRET), it)
                assertFalse(it.contains(TOKEN), it)
            }
            assertOnlyExposeSecretIsRaw(ClientSecret(SECRET), SECRET)
            assertOnlyExposeSecretIsRaw(AccessToken(TOKEN), TOKEN)
        }

    private fun assertOnlyExposeSecretIsRaw(
        holder: Any,
        raw: String,
    ) {
        for (m in holder.javaClass.methods) {
            val accessor = m.parameterCount == 0 && !Modifier.isStatic(m.modifiers) && m.returnType == String::class.java
            if (accessor && raw == m.invoke(holder)) assertEquals("exposeSecret", m.name)
        }
    }

    private companion object {
        const val SECRET = "lgr_cs_redact_0123456789abcdefghijklmnopqrstu"
        const val TOKEN = "lgr_at_redact_token"
    }
}
