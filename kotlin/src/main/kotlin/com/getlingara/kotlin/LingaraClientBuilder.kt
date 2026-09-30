package com.getlingara.kotlin

import com.getlingara.kotlin.internal.Policy
import com.getlingara.kotlin.internal.UserAgent
import kotlinx.coroutines.delay
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Marks the client DSL, so an inner lambda cannot reach an outer builder by accident. */
@DslMarker
public annotation class LingaraDsl

/**
 * A client's options: every knob of the contract is one property or function (ADR 29.9.26s D4).
 * The conformance harness builds every client from these and nothing else.
 *
 * ```
 * val client = LingaraClient {
 *     clientCredentials(System.getenv("LINGARA_CLIENT_ID"), System.getenv("LINGARA_CLIENT_SECRET"))
 * }
 * ```
 */
@LingaraDsl
public class LingaraClientBuilder internal constructor() {
    internal var clientId: String? = null
    internal var clientSecret: ClientSecret? = null
    internal var secretPost: Boolean = false
    internal var onDeprecation: ((DeprecationNotice) -> Unit)? = null

    /** The scopes to ask the token endpoint for; empty sends no `scope`, meaning all allowed. */
    public var scopes: List<String> = emptyList()

    /** A caller's own token source, replacing the one `clientCredentials` builds. Exclusive with it. */
    public var tokenSource: TokenSource? = null

    /** The API's base URL. */
    public var baseUrl: URI = URI.create("https://api.getlingara.com")

    /** The token endpoint. */
    public var tokenUrl: URI = URI.create("https://api.getlingara.com/oauth/token")

    /** Pins every `/v1` request to a `Lingara-Version` (K2); nothing is validated beyond non-empty. */
    public var version: String? = null

    /** Tries per HTTP request, the first included; 1 turns retries off (K4). */
    public var maxAttempts: Int = 3

    /** The longest `Retry-After` the client waits out; a longer one is thrown at once. */
    public var retryAfterCap: Duration = 60.seconds

    /** Fails a stream after this long with no byte while a read is pending (K5); real time. */
    public var streamIdleTimeout: Duration = 120.seconds

    /** Bounds each HTTP attempt of the token exchange, response body included; real time. */
    public var tokenRequestTimeout: Duration = 30.seconds

    /** A product token appended, after one space, to the library's own `User-Agent` (K6). */
    public var userAgentSuffix: String? = null

    /** What a token's stale point and an HTTP-date `Retry-After` are read against: a testing seam. */
    public var clock: Clock = Clock.systemUTC()

    /** What waits out a `Retry-After`: a testing seam. The default `delay` is cancellable. */
    public var sleeper: suspend (Duration) -> Unit = { delay(it) }

    /** The HTTP client, for proxies, TLS and executors; `null` builds one with a 30 s connect timeout. */
    public var httpClient: HttpClient? = null

    /** Authenticates with the client-credentials grant (K1). Omit it for a credential-free client. */
    public fun clientCredentials(
        id: String,
        secret: String,
    ) {
        clientId = id
        clientSecret = ClientSecret(secret)
    }

    /** Sends the credentials in the token request's form instead of the default Basic header. */
    public fun clientSecretPost() {
        secretPost = true
    }

    /**
     * Called once per response under a deprecated version, before the call returns. Without one,
     * the client logs one `System.Logger` warning per version id. A hook that throws never fails
     * the call.
     */
    public fun onDeprecation(hook: (DeprecationNotice) -> Unit) {
        onDeprecation = hook
    }

    internal fun policy(): Policy = Policy(maxAttempts, retryAfterCap, clock, sleeper)

    internal fun build(): LingaraClient {
        require(version?.isEmpty() != true) { "version needs a version id" }
        require(tokenSource == null || clientId == null) { "tokenSource and clientCredentials are exclusive" }
        require(maxAttempts >= 1) { "maxAttempts needs at least 1" }
        val http = httpClient ?: HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30)).build()
        return LingaraClient(this, tokens(http), http)
    }

    private fun tokens(http: HttpClient): TokenSource? {
        val id = clientId
        val secret = clientSecret
        if (tokenSource != null || id == null || secret == null) return tokenSource
        return ClientCredentialsTokenSource(id, secret, TokenExchange(this, http, UserAgent.of(userAgentSuffix)))
    }
}
