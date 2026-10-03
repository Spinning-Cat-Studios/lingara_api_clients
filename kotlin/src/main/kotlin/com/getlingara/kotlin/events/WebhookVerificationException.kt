package com.getlingara.kotlin.events

/**
 * A webhook delivery that failed verification (CONTRACT.md appendix W; ADR 30.9.26aa D4).
 *
 * It is deliberately not a [com.getlingara.kotlin.LingaraException]: no Lingara server answered
 * anything, and a `catch (e: LingaraException)` around API calls must not also swallow a forged
 * webhook. Its message never contains a secret, a signature or the body.
 */
public class WebhookVerificationException internal constructor(
    /** Why verification failed. */
    public val reason: Reason,
    message: String,
) : RuntimeException("${reason.wireName}: $message") {
    /** Why a delivery failed verification: appendix W's six reasons. */
    public enum class Reason {
        /** A `webhook-id`, `webhook-timestamp` or `webhook-signature` is absent. */
        MISSING_HEADER,

        /** `webhook-timestamp` is not one or more ASCII digits. */
        MALFORMED_HEADER,

        /** The timestamp is more than 300 s before now. */
        TIMESTAMP_TOO_OLD,

        /** The timestamp is more than 300 s after now. */
        TIMESTAMP_TOO_NEW,

        /** No `v1` signature matches any secret. */
        NO_MATCHING_SIGNATURE,

        /** The signature matched, but the body is not an event envelope with that id. */
        MALFORMED_PAYLOAD,
        ;

        /** The reason as the contract spells it, such as `no_matching_signature`. */
        public val wireName: String get() = name.lowercase()
    }
}
