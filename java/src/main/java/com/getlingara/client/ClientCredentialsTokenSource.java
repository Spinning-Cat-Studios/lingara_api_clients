package com.getlingara.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.getlingara.client.internal.ErrorMapper;
import com.getlingara.client.internal.Retry;
import com.getlingara.client.internal.SharedExecutor;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The OAuth 2.0 client-credentials grant: the {@link TokenSource} a client builds from {@link
 * LingaraClient.Builder#clientCredentials} (CONTRACT.md K1; ADR 29.9.26r D5).
 *
 * <p>It caches one token and replaces it {@code min(60 s, expires_in / 2)} before it expires. N
 * concurrent callers share one exchange, which runs on the library's shared executor and never on a
 * caller's thread, so a caller that is interrupted abandons only its own wait: the exchange runs on
 * and caches its token for the next caller. Each HTTP attempt of the exchange is bounded by the
 * token request timeout, response body included, because no waiter can cancel it.
 */
public final class ClientCredentialsTokenSource implements TokenSource {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final String clientId;
  private final ClientSecret secret;
  private final Exchange exchange;
  private final ReentrantLock lock = new ReentrantLock();
  // Guarded by lock: the cached token, or the one flight every caller waits on.
  private Cached cached;
  private CompletableFuture<AccessToken> flight;

  /**
   * Everything the exchange needs besides the credentials.
   *
   * @param http the HTTP client
   * @param tokenUrl the token endpoint
   * @param userAgent the K6 header
   * @param secretPost whether to send the credentials in the form (client_secret_post)
   * @param scopes the scopes to ask for, or empty for none
   * @param policy the K4 knobs and seams
   * @param timeout the bound on each HTTP attempt
   */
  record Exchange(
      HttpClient http,
      URI tokenUrl,
      String userAgent,
      boolean secretPost,
      List<String> scopes,
      Retry.Policy policy,
      Duration timeout) {}

  private record Cached(AccessToken token, Instant staleAt) {}

  ClientCredentialsTokenSource(String clientId, ClientSecret secret, Exchange exchange) {
    this.clientId = clientId;
    this.secret = secret;
    this.exchange = exchange;
  }

  /**
   * Returns the cached token while it is fresh, and otherwise waits on the one exchange, starting
   * it if none is running.
   *
   * @throws CancellationException when the waiting thread is interrupted, its flag restored; the
   *     exchange goes on
   */
  @Override
  public AccessToken token() {
    CompletableFuture<AccessToken> wait;
    boolean start = false;
    lock.lock();
    try {
      if (cached != null && exchange.policy().clock().instant().isBefore(cached.staleAt())) {
        return cached.token();
      }
      if (flight == null) {
        flight = new CompletableFuture<>();
        start = true;
      }
      wait = flight;
    } finally {
      lock.unlock();
    }
    if (start) {
      SharedExecutor.pool().execute(() -> fly(wait));
    }
    return await(wait);
  }

  /** Forgets {@code token} only if it is still cached; during an exchange it is a no-op. */
  @Override
  public void invalidate(AccessToken token) {
    lock.lock();
    try {
      if (cached != null && cached.token().equals(token)) {
        cached = null;
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Waits on a copy, so a waiter that leaves can neither cancel nor complete the shared flight.
   * {@code get()} answers an interrupt where {@code join()} would not.
   */
  private static AccessToken await(CompletableFuture<AccessToken> flight) {
    try {
      return flight.copy().get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Retry.cancelled();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException failure) {
        // Every waiter gets the same instance.
        throw failure;
      }
      throw new TransportException(TransportKind.CONNECT, e.getCause());
    }
  }

  /**
   * Runs the exchange, then writes the cache and only afterwards completes the flight: a waiter
   * that wakes and invalidates at once then clears the token it was handed. Nothing is cached on
   * failure.
   */
  private void fly(CompletableFuture<AccessToken> wait) {
    Cached result;
    try {
      result = exchange();
    } catch (RuntimeException failure) {
      settle(null);
      wait.completeExceptionally(failure);
      return;
    }
    settle(result);
    wait.complete(result.token());
  }

  private void settle(Cached result) {
    lock.lock();
    try {
      flight = null;
      if (result != null) {
        cached = result;
      }
    } finally {
      lock.unlock();
    }
  }

  private Cached exchange() {
    Retry.Policy policy = exchange.policy();
    Instant[] sentAt = new Instant[1];
    HttpResponse<String> response =
        Retry.withRetries(
            policy,
            () -> {
              // obtained_at is when the request that succeeded was sent.
              sentAt[0] = policy.clock().instant();
              return post();
            });
    int status = response.statusCode();
    byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
    if (status < 200 || status > 299) {
      throw ErrorMapper.refusal(
          ErrorMapper.Endpoint.TOKEN, status, response.headers(), body, policy.clock().instant());
    }
    return grant(body, sentAt[0]);
  }

  /** One attempt, awaited with the timeout so the bound covers the body too. */
  private HttpResponse<String> post() {
    CompletableFuture<HttpResponse<String>> pending =
        exchange.http().sendAsync(request(), HttpResponse.BodyHandlers.ofString());
    try {
      return pending.get(exchange.timeout().toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new TransportException(TransportKind.TIMEOUT, e);
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw Retry.cancelled();
    } catch (ExecutionException e) {
      throw ErrorMapper.transport(e.getCause(), false, List.of(secret.exposeSecret()));
    }
  }

  private HttpRequest request() {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(exchange.tokenUrl())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .header("User-Agent", exchange.userAgent())
            .POST(HttpRequest.BodyPublishers.ofString(form()));
    if (!exchange.secretPost()) {
      builder.header("Authorization", basic(clientId, secret.exposeSecret()));
    }
    return builder.build();
  }

  /** The grant, any scopes, and the credentials only under client_secret_post: never both. */
  private String form() {
    List<String> fields = new ArrayList<>();
    fields.add("grant_type=client_credentials");
    if (!exchange.scopes().isEmpty()) {
      fields.add("scope=" + encode(String.join(" ", exchange.scopes())));
    }
    if (exchange.secretPost()) {
      fields.add("client_id=" + encode(clientId));
      fields.add("client_secret=" + encode(secret.exposeSecret()));
    }
    return String.join("&", fields);
  }

  /**
   * {@code Basic base64(form(id) ":" form(secret))}, each half form-encoded per RFC 6749 §2.3.1:
   * space as {@code +}, and {@code *-._} left bare.
   */
  static String basic(String id, String secret) {
    String pair = encode(id) + ":" + encode(secret);
    return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** A 200's token and stale point; malformed unless Bearer with a token and a lifetime. */
  private static Cached grant(byte[] body, Instant sentAt) {
    JsonNode json;
    try {
      json = JSON.readTree(body);
    } catch (IOException e) {
      throw new TransportException(TransportKind.MALFORMED_RESPONSE, null);
    }
    JsonNode token = field(json, "access_token");
    JsonNode expiresIn = field(json, "expires_in");
    boolean bearer = field(json, "token_type").asText().equalsIgnoreCase("bearer");
    if (!token.isTextual() || !expiresIn.isNumber() || expiresIn.asDouble() < 0 || !bearer) {
      throw new TransportException(TransportKind.MALFORMED_RESPONSE, null);
    }
    Duration lifetime = Duration.ofMillis((long) (expiresIn.asDouble() * 1000));
    Duration skew = min(Duration.ofSeconds(60), lifetime.dividedBy(2));
    return new Cached(new AccessToken(token.asText()), sentAt.plus(lifetime).minus(skew));
  }

  /** The field, or a missing node, which is neither textual nor a number. */
  private static JsonNode field(JsonNode json, String name) {
    return json == null ? MissingNode.getInstance() : json.path(name);
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  @Override
  public String toString() {
    return "ClientCredentialsTokenSource{clientId="
        + clientId
        + ", clientSecret="
        + secret
        + ", tokenUrl="
        + exchange.tokenUrl()
        + "}";
  }
}
