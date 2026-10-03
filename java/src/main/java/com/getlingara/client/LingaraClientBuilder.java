package com.getlingara.client;

import com.getlingara.client.internal.Retry;
import com.getlingara.client.internal.UserAgent;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The options behind {@link LingaraClient.Builder}, kept in their own file so the client's stays
 * one screen of pipeline. Every method returns the public builder.
 */
abstract class LingaraClientBuilder {
  URI baseUrl = URI.create("https://api.getlingara.com");
  URI tokenUrl = URI.create("https://api.getlingara.com/oauth/token");
  String clientId;
  ClientSecret clientSecret;
  boolean clientSecretPost;
  List<String> scopes = List.of();
  TokenSource tokenSource;
  String version;
  Consumer<DeprecationNotice> onDeprecation;
  int maxAttempts = 3;
  Duration retryAfterCap = Duration.ofSeconds(60);
  Duration streamIdleTimeout = Duration.ofSeconds(120);
  Duration tokenRequestTimeout = Duration.ofSeconds(30);
  String userAgentSuffix;
  Clock clock = Clock.systemUTC();
  // Thread.sleep(Duration) is Java 19+; this one ends early on an interrupt.
  Sleeper sleeper = d -> TimeUnit.NANOSECONDS.sleep(d.toNanos());
  HttpClient httpClient;
  Duration requestTimeout;
  int tailMaxFailures = 8;

  LingaraClientBuilder() {}

  private LingaraClient.Builder self() {
    return (LingaraClient.Builder) this;
  }

  /**
   * Authenticates with the client-credentials grant (K1). Omit it for a credential-free client.
   *
   * @param clientId the client id, which is rendered
   * @param clientSecret the client secret, which never is
   * @return this builder
   */
  public LingaraClient.Builder clientCredentials(String clientId, String clientSecret) {
    this.clientId = Objects.requireNonNull(clientId, "clientId");
    this.clientSecret = new ClientSecret(clientSecret);
    return self();
  }

  /**
   * Sends the credentials in the token request's form (client_secret_post) instead of the default
   * client_secret_basic header.
   *
   * @return this builder
   */
  public LingaraClient.Builder clientSecretPost() {
    this.clientSecretPost = true;
    return self();
  }

  /**
   * Asks the token endpoint for these scopes. Without it no {@code scope} is sent, which means
   * every scope the client is allowed.
   *
   * @param scopes the scopes
   * @return this builder
   */
  public LingaraClient.Builder scopes(String... scopes) {
    this.scopes = List.of(scopes);
    return self();
  }

  /**
   * Replaces the {@link ClientCredentialsTokenSource} built from {@code clientCredentials} with a
   * caller's own. The two cannot be combined.
   *
   * @param tokenSource the token source
   * @return this builder
   */
  public LingaraClient.Builder tokenSource(TokenSource tokenSource) {
    this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
    return self();
  }

  /**
   * Sets the API's base URL; the default is {@code https://api.getlingara.com}.
   *
   * @param baseUrl the base URL
   * @return this builder
   */
  public LingaraClient.Builder baseUrl(URI baseUrl) {
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
    return self();
  }

  /**
   * Sets the token endpoint; the default is {@code https://api.getlingara.com/oauth/token}.
   *
   * @param tokenUrl the token endpoint
   * @return this builder
   */
  public LingaraClient.Builder tokenUrl(URI tokenUrl) {
    this.tokenUrl = Objects.requireNonNull(tokenUrl, "tokenUrl");
    return self();
  }

  /**
   * Pins every {@code /v1} request to a {@code Lingara-Version} (K2). Nothing is validated beyond
   * non-empty: the server's {@code 400 api_version_unknown} is the answer.
   *
   * @param version the version id
   * @return this builder
   */
  public LingaraClient.Builder version(String version) {
    this.version = Objects.requireNonNull(version, "version");
    return self();
  }

  /**
   * Called once per response under a deprecated version, before the call returns. Without one, the
   * client logs one {@code System.Logger} warning per version id. A hook that throws never fails
   * the call.
   *
   * @param onDeprecation the hook
   * @return this builder
   */
  public LingaraClient.Builder onDeprecation(Consumer<DeprecationNotice> onDeprecation) {
    this.onDeprecation = Objects.requireNonNull(onDeprecation, "onDeprecation");
    return self();
  }

  /**
   * Sets the tries per HTTP request, the first included: 3 by default, and 1 turns retries off
   * (K4).
   *
   * @param maxAttempts the tries
   * @return this builder
   */
  public LingaraClient.Builder maxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
    return self();
  }

  /**
   * Sets the longest {@code Retry-After} the client waits out: 60 s by default. A longer one is
   * raised at once, with {@code retryAfter()} set.
   *
   * @param retryAfterCap the cap
   * @return this builder
   */
  public LingaraClient.Builder retryAfterCap(Duration retryAfterCap) {
    this.retryAfterCap = Objects.requireNonNull(retryAfterCap, "retryAfterCap");
    return self();
  }

  /**
   * Fails a stream after this long with no byte while a read is pending: 120 s by default (K5). It
   * is enforced by the stream itself, so it holds on a caller's own {@code httpClient} too.
   *
   * @param streamIdleTimeout the timeout
   * @return this builder
   */
  public LingaraClient.Builder streamIdleTimeout(Duration streamIdleTimeout) {
    this.streamIdleTimeout = Objects.requireNonNull(streamIdleTimeout, "streamIdleTimeout");
    return self();
  }

  /**
   * Bounds each HTTP attempt of the token exchange, response body included: 30 s by default. {@code
   * Retry-After} waits between attempts are not counted.
   *
   * @param tokenRequestTimeout the timeout
   * @return this builder
   */
  public LingaraClient.Builder tokenRequestTimeout(Duration tokenRequestTimeout) {
    this.tokenRequestTimeout = Objects.requireNonNull(tokenRequestTimeout, "tokenRequestTimeout");
    return self();
  }

  /**
   * Appends a product token, after one space, to the library's own {@code User-Agent}, which always
   * comes first (K6).
   *
   * @param userAgentSuffix the product token
   * @return this builder
   */
  public LingaraClient.Builder userAgentSuffix(String userAgentSuffix) {
    this.userAgentSuffix = userAgentSuffix;
    return self();
  }

  /**
   * Replaces {@code Clock.systemUTC()}, which a token's stale point and an HTTP-date {@code
   * Retry-After} are read against. It is a testing seam.
   *
   * @param clock the clock
   * @return this builder
   */
  public LingaraClient.Builder clock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
    return self();
  }

  /**
   * Replaces the {@code Retry-After} wait. It is a testing seam.
   *
   * @param sleeper the sleeper
   * @return this builder
   */
  public LingaraClient.Builder sleeper(Sleeper sleeper) {
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    return self();
  }

  /**
   * Sets the HTTP client, for proxies, TLS and executors. The default is one this library builds,
   * with a 30 s connect timeout.
   *
   * @param httpClient the HTTP client
   * @return this builder
   */
  public LingaraClient.Builder httpClient(HttpClient httpClient) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    return self();
  }

  /**
   * Bounds the wait for each request's response headers, never a body. Absent by default: the
   * contract sets no JSON-call timeout, and a caller may interrupt the thread instead.
   *
   * @param requestTimeout the timeout
   * @return this builder
   */
  public LingaraClient.Builder requestTimeout(Duration requestTimeout) {
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    return self();
  }

  /**
   * Sets how many consecutive failures {@code tailEvents} rides out before it raises the last: 8 by
   * default, which is 91 s of reconnect sleeps (CONTRACT.md K5a). Raise it for a game that should
   * wait out a longer outage.
   *
   * @param tailMaxFailures the bound
   * @return this builder
   */
  public LingaraClient.Builder tailMaxFailures(int tailMaxFailures) {
    this.tailMaxFailures = tailMaxFailures;
    return self();
  }

  /**
   * Builds the client.
   *
   * @return the client
   * @throws IllegalStateException for an empty {@code version}, for {@code tokenSource} beside
   *     {@code clientCredentials}, and for {@code maxAttempts} or {@code tailMaxFailures} below 1
   */
  public LingaraClient build() {
    if (version != null && version.isEmpty()) {
      throw new IllegalStateException("version needs a version id");
    }
    if (tokenSource != null && clientId != null) {
      throw new IllegalStateException("tokenSource and clientCredentials are exclusive");
    }
    if (maxAttempts < 1) {
      throw new IllegalStateException("maxAttempts needs at least 1");
    }
    if (tailMaxFailures < 1) {
      throw new IllegalStateException("tailMaxFailures needs at least 1");
    }
    HttpClient http =
        httpClient != null
            ? httpClient
            : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    return new LingaraClient(this, tokens(http), http);
  }

  Retry.Policy policy() {
    return new Retry.Policy(maxAttempts, retryAfterCap, clock, sleeper);
  }

  private TokenSource tokens(HttpClient http) {
    if (tokenSource != null || clientId == null) {
      return tokenSource;
    }
    ClientCredentialsTokenSource.Exchange exchange =
        new ClientCredentialsTokenSource.Exchange(
            http,
            tokenUrl,
            UserAgent.of(userAgentSuffix),
            clientSecretPost,
            scopes,
            policy(),
            tokenRequestTimeout);
    return new ClientCredentialsTokenSource(clientId, clientSecret, exchange);
  }
}
