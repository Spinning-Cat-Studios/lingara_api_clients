package com.getlingara.kotlin.internal

import com.getlingara.kotlin.DeprecationNotice
import java.net.URI
import java.net.http.HttpHeaders
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * K2, per client (ADR 29.9.26s D9): reads the served version off each response, reports a
 * deprecation once per response to the hook or, with no hook, warns once per version id, and warns
 * once per served id that is not the version the models were generated from (ADR 30.9.26a).
 */
internal class Deprecations(
    private val hook: ((DeprecationNotice) -> Unit)?,
) {
    private val warned = ConcurrentHashMap.newKeySet<String>()

    // Its own set: sharing `warned` would let a version that is both deprecated and mismatched warn
    // only once in total.
    private val mismatched = ConcurrentHashMap.newKeySet<String>()

    /** Reports any deprecation or version mismatch, then returns the `Lingara-Version` echo. */
    fun observe(
        headers: HttpHeaders,
        requestUri: URI,
    ): String? {
        notice(headers, requestUri)?.let(::report)
        val served = headers.firstValue("Lingara-Version").orElse(null)
        served?.let(::checkGenerated)
        return served
    }

    private fun report(notice: DeprecationNotice) {
        val hook = hook ?: return warnOnce(notice)
        try {
            hook(notice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.log(System.Logger.Level.DEBUG, "the Lingara deprecation hook threw; the call continues", e)
        }
    }

    private fun warnOnce(notice: DeprecationNotice) {
        // An absent echo counts as one id: the empty string.
        val id = notice.version ?: ""
        if (warned.add(id)) {
            val name = id.ifEmpty { "(unnamed)" }
            val sunset = notice.sunset?.let { "; sunset $it" } ?: ""
            LOG.log(System.Logger.Level.WARNING, "Lingara API version $name is deprecated$sunset. See GET /v1/versions.")
        }
    }

    private fun checkGenerated(served: String) {
        val generated = BuildInfo.GENERATED_FOR_VERSION
        if (served != generated && mismatched.add(served)) {
            LOG.log(
                System.Logger.Level.WARNING,
                "Lingara API version $served served this response, but this library's models were " +
                    "generated for $generated; response shapes may differ. Pin the OAuth client to " +
                    "$generated or upgrade the library.",
            )
        }
    }

    companion object {
        val LOG: System.Logger = System.getLogger("com.getlingara.kotlin")

        /** The notice a response carries, or `null` when it has no `Deprecation` header. */
        fun notice(
            headers: HttpHeaders,
            requestUri: URI,
        ): DeprecationNotice? {
            val raw = headers.firstValue("Deprecation").orElse(null) ?: return null
            val sunset = headers.firstValue("Sunset").orElse(null)
            return DeprecationNotice(
                version = headers.firstValue("Lingara-Version").orElse(null),
                deprecatedAt = deprecatedAt(raw),
                sunsetAt = sunset?.let(::imfFixdate),
                link = headers.firstValue("Link").orElse(null)?.let { link(it, requestUri) },
                deprecation = raw,
                sunset = sunset,
            )
        }

        fun deprecatedAt(value: String): Instant? {
            val trimmed = value.trim()
            if (!trimmed.startsWith("@") || !trimmed.substring(1).matches(Regex("-?\\d{1,18}"))) return null
            return Instant.ofEpochSecond(trimmed.substring(1).toLong())
        }

        fun imfFixdate(value: String): Instant? =
            try {
                ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            } catch (e: DateTimeParseException) {
                null
            }

        fun link(
            raw: String,
            requestUri: URI,
        ): DeprecationNotice.Link {
            val trimmed = raw.trim()
            val close = trimmed.indexOf('>')
            if (!trimmed.startsWith("<") || close < 0) return DeprecationNotice.Link(raw, null)
            return try {
                DeprecationNotice.Link(raw, requestUri.resolve(trimmed.substring(1, close)))
            } catch (e: IllegalArgumentException) {
                DeprecationNotice.Link(raw, null)
            }
        }
    }
}
