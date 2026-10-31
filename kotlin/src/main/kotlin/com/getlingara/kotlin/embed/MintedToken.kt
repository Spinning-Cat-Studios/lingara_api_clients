package com.getlingara.kotlin.embed

import com.getlingara.kotlin.AccessToken
import com.getlingara.kotlin.TransportException
import com.getlingara.kotlin.TransportKind
import com.getlingara.kotlin.internal.LingaraJson
import com.getlingara.kotlin.model.EmbedToken
import com.getlingara.kotlin.model.UpdateAvailable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Duration

/**
 * A player's embed token, minted by [createEmbedToken] (ADR 1.10.26w D3). Its [token] is a bearer
 * credential for one player's data for 900 seconds, so it renders as `[REDACTED]` like every other
 * token (CONTRACT.md K1); `token.exposeSecret()` is the one way to read it, to hand it to the
 * player kit.
 *
 * A final class and not a data class, for the reason `ClientSecret` gives: a data class's
 * `toString` would print every property.
 */
public class MintedToken internal constructor(
    answer: EmbedToken,
    notice: UpdateAvailable?,
) {
    /** The token, which renders as `[REDACTED]`; read it with `exposeSecret()`. */
    public val token: AccessToken = AccessToken(answer.token)

    /** When the token expires, as the server's RFC 3339 string. */
    public val expiresAt: String = answer.expiresAt

    /**
     * How long the token lives from this answer (900 seconds). A caller that hands the token to a
     * device whose clock cannot be trusted uses this rather than [expiresAt].
     */
    public val expiresIn: Duration = Duration.ofSeconds(answer.expiresIn)

    /**
     * The player's pairwise `lgr_sub_`, stable across mints. Store it beside the player: it is how
     * an event names them.
     */
    public val subject: String = answer.subject

    /** The scopes granted: every handable scope the client holds when the request named none. */
    public val scopes: List<String> = answer.scopes.toList()

    /** Whether the player has linked a Lingara account. */
    public val accountLinked: Boolean = answer.accountLinked

    /**
     * Lingara's notice that the `embed_version` the request named is behind, deprecated or
     * withdrawn, copied verbatim; null when the request named none, the version is current, or the
     * notice was malformed. A notice is advisory, so a bad one is dropped and never costs the token
     * (ADR 31.10.26g D3).
     */
    public val updateAvailable: UpdateAvailable? = notice

    override fun toString(): String =
        "MintedToken(token=$token, expiresAt=$expiresAt, expiresIn=$expiresIn, subject=$subject, " +
            "scopes=$scopes, accountLinked=$accountLinked, updateAvailable=$updateAvailable)"

    internal companion object {
        private const val PREFIX = "lgr_et_"

        // The schema's maxLength, in code points (never UTF-16 units), so every library agrees on
        // a notice near the limit.
        private const val MESSAGE_MAX = 280

        /**
         * Builds a minted token from a mint's answer. The generated decoder already refuses a
         * missing or mistyped field; the token must also start `lgr_et_`. A failure is
         * `malformed_response` with no cause, so the body (and the token it may hold) cannot leak
         * through one. The notice is left out of the typed decode, so a malformed one cannot fail
         * it, and is converted apart (ADR 31.10.26g D3).
         */
        fun of(answer: JsonObject): MintedToken {
            val decoded =
                try {
                    LingaraJson.decodeFromJsonElement(EmbedToken.serializer(), JsonObject(answer - "update_available"))
                } catch (e: IllegalArgumentException) {
                    null
                }
            if (decoded == null || !decoded.token.startsWith(PREFIX)) throw malformed()
            return MintedToken(decoded, updateFrom(answer["update_available"]))
        }

        /**
         * The notice, when it is an object whose `deprecated` is a boolean and whose `latest` and
         * `message` are each present and a string or null, `message` at most 280 code points;
         * otherwise null. It reads the raw element by hand, since `LingaraJson` reads a missing
         * key as null; unknown keys are ignored.
         */
        private fun updateFrom(value: JsonElement?): UpdateAvailable? {
            val notice = value as? JsonObject ?: return null
            val deprecated = (notice["deprecated"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            val latest = notice["latest"]
            val message = notice["message"]
            if (deprecated == null || !nullableString(latest) || !nullableString(message)) return null
            val text = (message as JsonPrimitive).contentOrNull
            if (text != null && text.codePointCount(0, text.length) > MESSAGE_MAX) return null
            return UpdateAvailable((latest as JsonPrimitive).contentOrNull, deprecated, text)
        }

        private fun nullableString(value: JsonElement?): Boolean = value == JsonNull || (value is JsonPrimitive && value.isString)

        /** `malformed_response`, carrying nothing of the answer. */
        fun malformed(): TransportException = TransportException(TransportKind.MALFORMED_RESPONSE, null)
    }
}
