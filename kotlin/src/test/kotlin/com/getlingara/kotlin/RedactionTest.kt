package com.getlingara.kotlin

import com.getlingara.kotlin.embed.MintedToken
import com.getlingara.kotlin.internal.ErrorMapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.Modifier
import java.time.Duration
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

    /**
     * 1.10.26w AC18: MintedToken.toString() redacts and token.exposeSecret() returns the value,
     * while the other five fields read as sent; a fixture missing subject is refused as
     * malformed_response, and that error renders no token.
     */
    @Test
    fun aMintedTokenRendersRedacted() {
        val fields =
            mapOf(
                "token" to JsonPrimitive(MINTED),
                "expires_at" to JsonPrimitive("2026-10-01T09:27:44Z"),
                "expires_in" to JsonPrimitive(900),
                "subject" to JsonPrimitive("lgr_sub_redact"),
                "scopes" to JsonArray(listOf(JsonPrimitive("embed:play"))),
                "account_linked" to JsonPrimitive(false),
            )
        val token = MintedToken.of(JsonObject(fields))
        assertFalse(token.toString().contains(MINTED), token.toString())
        assertTrue(token.toString().contains("[REDACTED]"))
        assertFalse(ApiResponse(token, null).toString().contains(MINTED))
        assertEquals(MINTED, token.token.exposeSecret())
        assertEquals("2026-10-01T09:27:44Z", token.expiresAt)
        assertEquals(Duration.ofSeconds(900), token.expiresIn)
        assertEquals("lgr_sub_redact", token.subject)
        assertEquals(listOf("embed:play"), token.scopes)
        assertFalse(token.accountLinked)

        val refused = assertFailsWith<TransportException> { MintedToken.of(JsonObject(fields - "subject")) }
        assertEquals(TransportKind.MALFORMED_RESPONSE, refused.kind)
        renderings(refused).forEach { assertFalse(it.contains(MINTED), it) }
        val notEmbed = fields + ("token" to JsonPrimitive("lgr_at_not_an_embed_token"))
        assertFailsWith<TransportException> { MintedToken.of(JsonObject(notEmbed)) }
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
        const val MINTED = "lgr_et_redact_0123456789abcdefghijklmnopqrstuvwxyz0"
    }
}
