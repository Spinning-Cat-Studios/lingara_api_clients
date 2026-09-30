package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

class ErrorMappingTest {
  private static final String MAINTENANCE = "Service is under maintenance. Please try again later.";

  /**
   * 29.9.26r AC10: a plain-text 503 from either endpoint is MaintenanceException; a non-envelope
   * /v1 502 is ApiException with code http_502; a non-RFC 6749 token-endpoint 500 is OAuthException
   * with error http_500.
   */
  @Test
  void responsesMapToTheErrorFamily() throws Exception {
    Fakes.Handler maintenance =
        e -> Fakes.respond(e, 503, "text/plain; charset=utf-8", MAINTENANCE);
    try (Fakes.Server server =
        new Fakes.Server()
            .on("/v1/usage", maintenance)
            .on("/v1/versions", e -> Fakes.respond(e, 502, "text/html", "<html>bad gateway</html>"))
            .on("/oauth/token", maintenance)) {
      LingaraClient free = LingaraClient.builder().baseUrl(server.uri()).build();
      assertEquals(MAINTENANCE, assertThrows(MaintenanceException.class, free::getUsage).body());
      ApiException proxy = assertThrows(ApiException.class, free::listApiVersions);
      assertEquals(502, proxy.status());
      assertEquals("http_502", proxy.code());
      assertEquals(
          MAINTENANCE,
          assertThrows(MaintenanceException.class, credentialed(server)::getUsage).body());
    }
    try (Fakes.Server server =
        new Fakes.Server().on("/oauth/token", e -> Fakes.respond(e, 500, null, ""))) {
      OAuthException e = assertThrows(OAuthException.class, credentialed(server)::getUsage);
      assertEquals(500, e.status());
      assertEquals("http_500", e.error());
      assertEquals(Optional.empty(), e.description());
    }
  }

  private static LingaraClient credentialed(Fakes.Server server) {
    return LingaraClient.builder()
        .baseUrl(server.uri())
        .tokenUrl(server.uri("/oauth/token"))
        .clientCredentials("lgr_cid_test", "lgr_cs_test")
        .build();
  }

  private static TransportKind kindOf(LingaraClient client) {
    return assertThrows(TransportException.class, client::listApiVersions).kind();
  }

  private static LingaraClient at(URI base) {
    return LingaraClient.builder().baseUrl(base).build();
  }

  /**
   * 29.9.26r AC11: garbage answering a ClientHello is TLS; a refused port, a connect timeout and a
   * listener that closes before the status line are CONNECT; a listener that never answers, with
   * requestTimeout set, is TIMEOUT; a body cut mid-read is RESET.
   */
  @Test
  void transportFailuresMapToTheirKinds() throws Exception {
    try (Scripted.Listener garbage =
        new Scripted.Listener(s -> Scripted.Listener.write(s, "this is not TLS at all\r\n\r\n"))) {
      assertEquals(TransportKind.TLS, kindOf(at(garbage.uri("https"))));
    }
    URI refused;
    try (Scripted.Listener closed = new Scripted.Listener(s -> {})) {
      refused = closed.uri("http");
    }
    assertEquals(TransportKind.CONNECT, kindOf(at(refused)));
    LingaraClient stub =
        LingaraClient.builder().baseUrl(refused).httpClient(new ConnectTimeoutClient()).build();
    assertEquals(TransportKind.CONNECT, kindOf(stub));
    try (Scripted.Listener hangUp = new Scripted.Listener(Scripted.Listener::readRequest)) {
      assertEquals(TransportKind.CONNECT, kindOf(at(hangUp.uri("http"))));
    }
    try (Scripted.Listener silent =
        new Scripted.Listener(
            s -> {
              Scripted.Listener.readRequest(s);
              Fakes.sleep(2000);
            })) {
      LingaraClient client =
          LingaraClient.builder()
              .baseUrl(silent.uri("http"))
              .requestTimeout(Duration.ofMillis(200))
              .build();
      assertEquals(TransportKind.TIMEOUT, kindOf(client));
    }
    try (Scripted.Listener cut =
        new Scripted.Listener(
            s -> {
              Scripted.Listener.readRequest(s);
              Scripted.Listener.write(
                  s,
                  "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                      + "Content-Length: 100\r\n\r\n{\"versions\":[");
            })) {
      assertEquals(TransportKind.RESET, kindOf(at(cut.uri("http"))));
    }
  }

  /** A caller's HttpClient whose every send fails as a connect timeout: loopback cannot. */
  private static final class ConnectTimeoutClient extends HttpClient {
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException {
      throw new HttpConnectTimeoutException("connect timed out");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      return CompletableFuture.failedFuture(new HttpConnectTimeoutException("connect timed out"));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> push) {
      return sendAsync(request, handler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      return null;
    }

    @Override
    public SSLParameters sslParameters() {
      return null;
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }
}
