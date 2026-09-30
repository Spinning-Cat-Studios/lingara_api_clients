package com.getlingara.kotlin

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeprecationTest {
    /** A /v1/versions answering under [version], deprecated when [deprecation] is set. */
    private fun versions(
        version: String,
        deprecation: String?,
        sunset: String?,
    ): com.getlingara.kotlin.Handler =
        { exchange ->
            exchange.responseHeaders.set("Lingara-Version", version)
            if (deprecation != null) {
                exchange.responseHeaders.set("Deprecation", deprecation)
                exchange.responseHeaders.set("Sunset", sunset)
                exchange.responseHeaders.set("Link", LINK)
            }
            Fakes.json(exchange, 200, """{"current":null,"versions":[]}""")
        }

    /** Captures the library's WARNING records. */
    private class Warnings : Handler() {
        val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun publish(record: LogRecord) {
            if (record.level.intValue() >= Level.WARNING.intValue()) messages.add(record.message)
        }

        override fun flush() = Unit

        override fun close() = Unit

        fun deprecated(): Int = messages.count { it.contains("is deprecated") }
    }

    /**
     * 29.9.26s AC24: a Deprecation header calls the hook once with parsed instants and a Link
     * resolved against the request URI; a response without one does not call it; an unparseable
     * header leaves its field null; a throwing hook does not fail the call; with no hook one
     * System.Logger warning is logged per version id.
     */
    @Test
    fun deprecationHookParsingAndWarnOnce() =
        runBlocking {
            val calls = Collections.synchronizedList(mutableListOf<DeprecationNotice>())
            val script =
                Fakes.script(
                    versions(CAT, "@1790812800", "Mon, 01 Mar 2027 00:00:00 GMT"),
                    versions(CAT, null, null),
                    versions(CAT, "yesterday", "soon"),
                )
            FakeServer().on("/v1/versions", script).use { server ->
                val client =
                    LingaraClient {
                        baseUrl = server.uri()
                        onDeprecation { calls.add(it) }
                    }
                client.listApiVersions()
                val notice = calls[0]
                assertEquals(CAT, notice.version)
                assertEquals(Instant.ofEpochSecond(1790812800), notice.deprecatedAt)
                assertEquals(Instant.ofEpochSecond(1803859200), notice.sunsetAt)
                assertEquals(LINK, notice.link?.raw)
                assertEquals(URI.create("${server.uri()}/v1/versions/$CAT"), notice.link?.target)
                client.listApiVersions()
                assertEquals(1, calls.size, "no Deprecation, no call")
                client.listApiVersions()
                val raw = calls[1]
                assertNull(raw.deprecatedAt)
                assertNull(raw.sunsetAt)
                assertEquals("yesterday", raw.deprecation)
                assertEquals("soon", raw.sunset)
            }
            assertThrowingHookAndWarnOnce()
        }

    private suspend fun assertThrowingHookAndWarnOnce() {
        val logger = Logger.getLogger("com.getlingara.kotlin")
        val warnings = Warnings()
        logger.addHandler(warnings)
        val script =
            Fakes.script(versions(CAT, "@1", "x"), versions(CAT, "@1", "x"), versions(CAT, "@1", "x"), versions(OTTER, "@1", "x"))
        try {
            FakeServer().on("/v1/versions", script).use { server ->
                val throwing =
                    LingaraClient {
                        baseUrl = server.uri()
                        onDeprecation { throw IllegalStateException("a caller's bug") }
                    }
                throwing.listApiVersions()
                assertEquals(0, warnings.deprecated(), "a hook replaces the warning")
                val quiet = LingaraClient { baseUrl = server.uri() }
                quiet.listApiVersions()
                quiet.listApiVersions()
                assertEquals(1, warnings.deprecated(), "one warning per version id")
                assertTrue(warnings.messages.any { it.contains(CAT) })
                quiet.listApiVersions()
                assertEquals(2, warnings.deprecated(), "a second id warns again")
            }
        } finally {
            logger.removeHandler(warnings)
        }
    }

    private companion object {
        const val CAT = "2026-09-affable-cat"
        const val OTTER = "2026-09-brave-otter"
        const val LINK = "</v1/versions/2026-09-affable-cat>; rel=\"deprecation\""
    }
}
