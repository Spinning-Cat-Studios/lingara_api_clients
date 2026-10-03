package com.getlingara.conformance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.client.LingaraClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Java library's conformance harness (conformance/README.md, Writing a harness; ADR 29.9.26r
 * D10). It runs every case through the library's public API: each client is built from the case's
 * {@code client} block through the builder only ({@link Rig}), a {@code parallel: n} step is n
 * threads released together, and {@code cancel_after_events: n} closes the stream after its n-th
 * event ({@link Observe}).
 */
public final class Harness {
  static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient control = HttpClient.newHttpClient();
  private final Env env;

  /**
   * What {@code conformance-server run} passes.
   *
   * @param base the client's API base URL
   * @param token the token endpoint
   * @param control the control surface
   * @param out the results file
   * @param only the case ids to run, or empty for all
   */
  record Env(String base, String token, String control, Path out, Set<String> only) {}

  private Harness(Env env) {
    this.env = env;
  }

  /**
   * Runs every case and exits non-zero when any fails.
   *
   * @param args unused
   * @throws Exception when the control surface cannot be reached
   */
  public static void main(String[] args) throws Exception {
    boolean passed = new Harness(readEnv()).run();
    System.exit(passed ? 0 : 1);
  }

  private static Env readEnv() {
    String only = System.getenv("LINGARA_CONFORMANCE_ONLY");
    return new Env(
        required("LINGARA_CONFORMANCE_BASE_URL"),
        required("LINGARA_CONFORMANCE_TOKEN_URL"),
        required("LINGARA_CONFORMANCE_CONTROL_URL"),
        Path.of(required("LINGARA_CONFORMANCE_OUT")),
        only == null || only.isBlank()
            ? Set.of()
            : Arrays.stream(only.split(",")).map(String::trim).collect(Collectors.toSet()));
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException(
          name + " is not set: run this through conformance-server run");
    }
    return value;
  }

  private boolean run() throws IOException, InterruptedException {
    List<String> ids = JSON.convertValue(call("GET", "/cases"), new TypeReference<>() {});
    boolean all = true;
    for (String id : ids) {
      if (env.only().isEmpty() || env.only().contains(id)) {
        all &= runCase(id);
      }
    }
    return all;
  }

  private boolean runCase(String id) throws IOException, InterruptedException {
    long started = System.nanoTime();
    JsonNode c = call("GET", "/cases/" + id);
    call("POST", "/cases/" + id + "/arm");
    List<String> client;
    try {
      client = steps(c);
    } catch (RuntimeException e) {
      client = List.of("harness: " + e);
    }
    JsonNode verdict = call("POST", "/cases/" + id + "/finish");
    return report(id, started, client, verdict.path("mismatches"));
  }

  private List<String> steps(JsonNode c) {
    JsonNode block = c.path("client");
    Rig rig = Rig.build(block, env.base(), env.token());
    List<String> mismatches = new ArrayList<>();
    for (JsonNode step : c.path("steps")) {
      if (step.has("advance_clock_s")) {
        rig.advance(step.path("advance_clock_s").asLong());
      }
      JsonNode expect = Compare.substitute(step.path("expect"), env.base());
      if (step.has("call") && step.has("expect")) {
        mismatches.addAll(Observe.runStep(rig, step.path("call"), expect));
      }
      for (String kind : List.of("events", "tail")) {
        if (step.has(kind) && step.has("expect")) {
          mismatches.addAll(EventSteps.runStep(rig, kind, step.path(kind), expect));
        }
      }
    }
    return mismatches;
  }

  private boolean report(String id, long started, List<String> client, JsonNode server)
      throws IOException {
    boolean pass = client.isEmpty() && server.isEmpty();
    ObjectNode line = JSON.createObjectNode();
    line.put("case", id);
    line.put("lang", "java");
    line.put("library_version", LingaraClient.LIBRARY_VERSION);
    line.put("result", pass ? "pass" : "fail");
    line.set("client_mismatches", JSON.valueToTree(client));
    line.set("server_mismatches", server.isMissingNode() ? JSON.createArrayNode() : server);
    line.put("duration_ms", (System.nanoTime() - started) / 1_000_000);
    Files.writeString(
        env.out(),
        JSON.writeValueAsString(line) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    if (!pass) {
      System.err.println("✗ " + id + ": client " + client + " server " + server);
    }
    return pass;
  }

  /** Calls the control surface and decodes its JSON answer. */
  private JsonNode call(String method, String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(env.control() + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> response = control.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() > 299) {
      throw new IOException(path + ": " + response.statusCode() + " " + response.body());
    }
    return JSON.readTree(response.body());
  }
}
