package com.getlingara.kotlin.embed

import com.getlingara.kotlin.AccessToken
import com.getlingara.kotlin.TransportException
import com.getlingara.kotlin.TransportKind
import com.getlingara.kotlin.internal.LingaraJson
import com.getlingara.kotlin.model.EmbedToken
import kotlinx.serialization.json.JsonObject
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

    override fun toString(): String =
        "MintedToken(token=$token, expiresAt=$expiresAt, expiresIn=$expiresIn, subject=$subject, " +
            "scopes=$scopes, accountLinked=$accountLinked)"

    internal companion object {
        private const val PREFIX = "lgr_et_"

        /**
         * Builds a minted token from a mint's answer. The generated decoder already refuses a
         * missing or mistyped field; the token must also start `lgr_et_`. A failure is
         * `malformed_response` with no cause, so the body (and the token it may hold) cannot leak
         * through one.
         */
        fun of(answer: JsonObject): MintedToken {
            val decoded =
                try {
                    LingaraJson.decodeFromJsonElement(EmbedToken.serializer(), answer)
                } catch (e: IllegalArgumentException) {
                    null
                }
            if (decoded == null || !decoded.token.startsWith(PREFIX)) throw malformed()
            return MintedToken(decoded)
        }

        /** `malformed_response`, carrying nothing of the answer. */
        fun malformed(): TransportException = TransportException(TransportKind.MALFORMED_RESPONSE, null)
    }
}
