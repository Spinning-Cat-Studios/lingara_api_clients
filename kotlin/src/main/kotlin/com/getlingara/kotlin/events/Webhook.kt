package com.getlingara.kotlin.events

import com.getlingara.kotlin.events.WebhookVerificationException.Reason
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies a Lingara webhook delivery: the Standard Webhooks scheme, keyed on `lgr_whsec_` secrets
 * (CONTRACT.md appendix W; ADR 30.9.26aa D4). It is native, on `javax.crypto.Mac`,
 * `MessageDigest.isEqual` and `java.util.Base64`, so the library gains no dependency.
 *
 * Hand it the body exactly as received, as bytes, before anything parses it: Ktor's
 * `call.receive<ByteArray>()`, or Spring's `@RequestBody body: ByteArray`. Then answer `2xx`
 * quickly and deduplicate by [Event.id]: delivery is at least once.
 *
 * ```
 * val webhook = Webhook(System.getenv("LINGARA_WEBHOOK_SECRET"))
 * val event = webhook.verify(body, headers)
 * ```
 *
 * [secrets] are one, or two while a rotation has both live: each `lgr_whsec_` followed by padded
 * standard base64 of at least 24 bytes, or the constructor throws `IllegalArgumentException`.
 * [clock] is what a delivery's timestamp is checked against, the testing seam the client's builder
 * also takes. It stores nothing and is safe for concurrent use; its secrets are never rendered.
 */
public class Webhook(
    vararg secrets: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val keys: List<ByteArray>

    init {
        require(secrets.isNotEmpty()) { "a Webhook needs at least one secret" }
        keys = secrets.map(::key)
    }

    /**
     * Verifies a delivery and parses it into an [Event]: a type this library does not know is an
     * [UnknownEvent], which you should still acknowledge. [headers] may name each header in any
     * case.
     *
     * @throws WebhookVerificationException when the delivery fails verification
     */
    public fun verify(
        body: ByteArray,
        headers: Map<String, List<String>>,
    ): Event {
        val id = check(body, headers)
        val event =
            try {
                Event.parse(String(body, Charsets.UTF_8))
            } catch (e: IllegalArgumentException) {
                // No cause: a JSON parser's message can quote the body.
                throw WebhookVerificationException(Reason.MALFORMED_PAYLOAD, "the body is not an event envelope")
            }
        if (event.id != id) throw WebhookVerificationException(Reason.MALFORMED_PAYLOAD, "the envelope's id is not webhook-id")
        return event
    }

    /**
     * Verifies a delivery's signature and nothing else, for a signed body that is not an event
     * envelope, such as an app-kit request. It never throws `MALFORMED_PAYLOAD`.
     *
     * @throws WebhookVerificationException when the signature does not verify
     */
    public fun verifySignature(
        body: ByteArray,
        headers: Map<String, List<String>>,
    ) {
        check(body, headers)
    }

    override fun toString(): String = "Webhook(secrets=${keys.size})"

    /** Appendix W steps 2–5; returns `webhook-id`. */
    private fun check(
        body: ByteArray,
        headers: Map<String, List<String>>,
    ): String {
        val id = header(headers, "webhook-id")
        val timestamp = header(headers, "webhook-timestamp")
        val signatures = header(headers, "webhook-signature")
        if (id == null || timestamp == null || signatures == null) {
            throw WebhookVerificationException(Reason.MISSING_HEADER, "a webhook-* header is missing")
        }
        checkTimestamp(timestamp)
        val signed = "$id.$timestamp.".toByteArray(Charsets.UTF_8) + body
        val candidates = v1Signatures(signatures)
        val matched = keys.map { hmac(it, signed) }.any { expected -> candidates.any { MessageDigest.isEqual(expected, it) } }
        if (!matched) throw WebhookVerificationException(Reason.NO_MATCHING_SIGNATURE, "no v1 signature matches a secret")
        return id
    }

    private fun checkTimestamp(timestamp: String) {
        if (!DIGITS.matches(timestamp)) {
            throw WebhookVerificationException(Reason.MALFORMED_HEADER, "webhook-timestamp is not whole seconds")
        }
        val at = BigInteger(timestamp)
        val now = BigInteger.valueOf(clock.instant().epochSecond)
        if (at < now - TOLERANCE_SECONDS) {
            throw WebhookVerificationException(Reason.TIMESTAMP_TOO_OLD, "webhook-timestamp is more than 300 s old")
        }
        if (at > now + TOLERANCE_SECONDS) {
            throw WebhookVerificationException(Reason.TIMESTAMP_TOO_NEW, "webhook-timestamp is more than 300 s ahead")
        }
    }

    private companion object {
        const val PREFIX = "lgr_whsec_"
        const val MIN_KEY_BYTES = 24
        val BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")
        val DIGITS = Regex("[0-9]+")
        val TOLERANCE_SECONDS: BigInteger = BigInteger.valueOf(300)

        /**
         * The HMAC key: the remainder after `lgr_whsec_`, matched as padded standard base64 before
         * it is decoded, because decoders differ in leniency.
         */
        fun key(secret: String): ByteArray {
            val rest = secret.removePrefix(PREFIX).takeIf { secret.startsWith(PREFIX) }.orEmpty()
            require(rest.length % 4 == 0 && BASE64.matches(rest)) {
                "a Lingara webhook secret is lgr_whsec_ followed by padded base64"
            }
            val key = Base64.getDecoder().decode(rest)
            require(key.size >= MIN_KEY_BYTES) { "a Lingara webhook secret decodes to at least 24 bytes" }
            return key
        }

        /** The first value of a header, its name matched case-insensitively; `null` when absent. */
        fun header(
            headers: Map<String, List<String>>,
            name: String,
        ): String? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) && it.value.isNotEmpty() }
                ?.value
                ?.first()

        /** Every `v1,<base64>` element that decodes; another version, or bad base64, is skipped. */
        fun v1Signatures(header: String): List<ByteArray> =
            header.split(" ").filter { it.startsWith("v1,") }.mapNotNull {
                try {
                    Base64.getDecoder().decode(it.substring(3))
                } catch (e: IllegalArgumentException) {
                    null
                }
            }

        fun hmac(
            key: ByteArray,
            content: ByteArray,
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(content)
        }
    }
}
