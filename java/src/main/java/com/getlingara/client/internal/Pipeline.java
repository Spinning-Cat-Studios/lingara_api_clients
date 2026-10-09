package com.getlingara.client.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getlingara.client.AccessToken;
import com.getlingara.client.ClientSecret;
import com.getlingara.client.TokenSource;
import com.getlingara.client.TransportException;
import com.getlingara.client.TransportKind;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The request pipeline behind every {@code /v1} call (ADR 29.9.26r D4, D5): auth and K1's one 401
 * retry, K4's loop, the refusal mapping, and a JSON body's decoding. It returns only a 2xx. Moved
 * out of {@code LingaraClient} unchanged (ADR 1.10.26w D8), so the client's file holds its
 * operations, as Kotlin's {@code internal/Pipeline.kt} and {@code Requests.kt} already do.
 */
public final class Pipeline {
  private static final int REFUSAL_BODY_BYTES = 64 * 1024;

  private final Target target;
  private final Retry.Policy policy;
  private final TokenSource tokens;
  private final HttpClient http;
  private final Deprecations deprecations;
  private final ObjectMapper mapper = jsonMapper();

  /**
   * What a pipeline shares with its client: the base URL, the pin, the two headers' inputs and the
   * per-request timeout.
   *
   * @param baseUrl the API's base URL, with no trailing slash
   * @param version the {@code Lingara-Version} pin, or null
   * @param userAgent the {@code User-Agent} header
   * @param secret the client secret, which no cause may carry, or null
   * @param requestTimeout each request's timeout, or null
   */
  public record Target(
      String baseUrl,
      String version,
      String userAgent,
      ClientSecret secret,
      Duration requestTimeout) {}

  /**
   * One {@code /v1} request, before auth: {@code headers} are its own ({@code Idempotency-Key},
   * {@code Last-Event-ID}), and {@code once} sends it with no K4 loop, as a tail open does (K5a).
   *
   * @param method the HTTP method
   * @param path the path and query, under the base URL
   * @param body the JSON body, or null for none
   * @param accept the {@code Accept} header
   * @param needsToken whether it is sent under the client's token
   * @param headers the request's own headers
   * @param once whether it is sent with no K4 loop
   */
  public record Call(
      String method,
      String path,
      byte[] body,
      String accept,
      boolean needsToken,
      Map<String, String> headers,
      boolean once) {
    /**
     * A call with no headers of its own, under K4's loop.
     *
     * @param method the HTTP method
     * @param path the path and query
     * @param body the JSON body, or null
     * @param accept the {@code Accept} header
     * @param needsToken whether it is sent under the client's token
     */
    public Call(String method, String path, byte[] body, String accept, boolean needsToken) {
      this(method, path, body, accept, needsToken, Map.of(), false);
    }
  }

  /**
   * A decoded answer and the API version it was served under.
   *
   * @param <T> the body's type
   * @param body the body
   * @param served the {@code Lingara-Version} the server answered under
   */
  public record Answer<T>(T body, Optional<String> served) {}

  /**
   * A pipeline over one client's options.
   *
   * @param target the base URL, pin, headers' inputs and timeout
   * @param policy K4's knobs
   * @param tokens the token source, or null for none
   * @param http the HTTP client
   * @param deprecations what observes {@code Lingara-Version} and the deprecation headers
   */
  public Pipeline(
      Target target,
      Retry.Policy policy,
      TokenSource tokens,
      HttpClient http,
      Deprecations deprecations) {
    this.target = target;
    this.policy = policy;
    this.tokens = tokens;
    this.http = http;
    this.deprecations = deprecations;
  }

  /**
   * The client's JSON mapper. An unknown response field is ignored, since fields are additive. A
   * request leaves out a null field and an empty optional list: {@code TutorTurnRequest.history}
   * defaults to an empty list the caller never set. A required field's own {@code
   * JsonInclude(ALWAYS)} still wins over both.
   *
   * @return a new mapper
   */
  public static ObjectMapper jsonMapper() {
    ObjectMapper mapper =
        new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setDefaultPropertyInclusion(
                JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
    mapper
        .configOverride(List.class)
        .setInclude(
            JsonInclude.Value.construct(
                JsonInclude.Include.NON_EMPTY, JsonInclude.Include.NON_NULL));
    return mapper;
  }

  /**
   * Returns this pipeline's JSON mapper.
   *
   * @return the mapper
   */
  public ObjectMapper mapper() {
    return mapper;
  }

  /**
   * A {@code GET} under the client's token.
   *
   * @param path the path and query
   * @param accept the {@code Accept} header
   * @return the call
   */
  public static Call get(String path, String accept) {
    return new Call("GET", path, null, accept, true);
  }

  /**
   * Sends {@code call} and decodes its body; one that does not decode is {@code
   * malformed_response}.
   *
   * @param <T> the body's type
   * @param call the request
   * @param type the body's class
   * @return the decoded body and the served version
   */
  public <T> Answer<T> json(Call call, Class<T> type) {
    HttpResponse<InputStream> response = send(call);
    Optional<String> served = observe(response);
    byte[] body;
    try (InputStream in = response.body()) {
      body = in.readAllBytes();
    } catch (IOException e) {
      throw failedRead(e);
    }
    try {
      return new Answer<>(mapper.readValue(body, type), served);
    } catch (IOException e) {
      throw new TransportException(TransportKind.MALFORMED_RESPONSE, e);
    }
  }

  /**
   * Sends {@code call} and reads nothing of its answer (ADR 1.10.26w D4): a {@code 204} has no
   * body, and a {@code 200} or any other 2xx that has one is still success, its body closed unread,
   * so a later API answering {@code 200 {}} is not a break. {@code Lingara-Version} and the
   * deprecation headers are still observed (K2).
   *
   * @param call the request
   * @return no body, and the served version
   */
  public Answer<Void> empty(Call call) {
    HttpResponse<InputStream> response = send(call);
    Optional<String> served = observe(response);
    Retry.discard(response);
    return new Answer<>(null, served);
  }

  /**
   * Reads {@code Lingara-Version} and the deprecation headers off a 2xx (K2).
   *
   * @param response the answer
   * @return the served version
   */
  public Optional<String> observe(HttpResponse<?> response) {
    return deprecations.observe(response.headers(), response.request().uri());
  }

  /**
   * Auth and the one 401 retry, K4's loop, and the refusal mapping: returns a 2xx.
   *
   * @param call the request
   * @return the 2xx answer, its body unread
   */
  public HttpResponse<InputStream> send(Call call) {
    HttpResponse<InputStream> response = authorised(call);
    int status = response.statusCode();
    if (status >= 200 && status <= 299) {
      return response;
    }
    byte[] body;
    try (InputStream in = response.body()) {
      body = in.readNBytes(REFUSAL_BODY_BYTES);
    } catch (IOException e) {
      body = new byte[0];
    }
    throw ErrorMapper.refusal(
        ErrorMapper.Endpoint.V1, status, response.headers(), body, policy.clock().instant());
  }

  /**
   * K1's one 401 retry: on a 401, forget that token (only if it is still cached), get another and
   * send once more, with a fresh K4 budget. A client with no token source sends an operation that
   * needs one without {@code Authorization}, and the server's 401 is the answer.
   */
  private HttpResponse<InputStream> authorised(Call call) {
    if (tokens == null || !call.needsToken()) {
      return attempts(call, null);
    }
    AccessToken first = tokens.token();
    HttpResponse<InputStream> response = attempts(call, first);
    if (response.statusCode() != 401) {
      return response;
    }
    Retry.discard(response);
    tokens.invalidate(first);
    return attempts(call, tokens.token());
  }

  private HttpResponse<InputStream> attempts(Call call, AccessToken token) {
    if (call.once()) {
      return sendOnce(call, token);
    }
    return Retry.withRetries(policy, () -> sendOnce(call, token));
  }

  private HttpResponse<InputStream> sendOnce(Call call, AccessToken token) {
    try {
      return http.send(request(call, token), HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Retry.cancelled();
    } catch (IOException e) {
      if (Thread.currentThread().isInterrupted()) {
        throw Retry.cancelled();
      }
      throw ErrorMapper.transport(e, false, secrets(token));
    }
  }

  private HttpRequest request(Call call, AccessToken token) {
    HttpRequest.BodyPublisher publisher =
        call.body() == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(call.body());
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(target.baseUrl() + call.path()))
            .method(call.method(), publisher)
            .header("Accept", call.accept())
            .header("User-Agent", target.userAgent());
    if (call.body() != null) {
      builder.header("Content-Type", "application/json");
    }
    if (token != null) {
      builder.header("Authorization", "Bearer " + token.exposeSecret());
    }
    if (target.version() != null) {
      builder.header("Lingara-Version", target.version());
    }
    call.headers().forEach(builder::header);
    if (target.requestTimeout() != null) {
      builder.timeout(target.requestTimeout());
    }
    return builder.build();
  }

  private RuntimeException failedRead(IOException failure) {
    if (Thread.currentThread().isInterrupted()) {
      return Retry.cancelled();
    }
    return ErrorMapper.transport(failure, true, secrets(null));
  }

  private List<String> secrets(AccessToken token) {
    List<String> out = new ArrayList<>();
    if (token != null) {
      out.add(token.exposeSecret());
    }
    if (target.secret() != null) {
      out.add(target.secret().exposeSecret());
    }
    return out;
  }

  /**
   * Encodes a request body as JSON.
   *
   * @param body the body
   * @return its bytes
   * @throws IllegalArgumentException when it cannot be encoded
   */
  public byte[] bytes(Object body) {
    try {
      return mapper.writeValueAsBytes(body);
    } catch (IOException e) {
      throw new IllegalArgumentException("the request body cannot be encoded as JSON", e);
    }
  }
}
