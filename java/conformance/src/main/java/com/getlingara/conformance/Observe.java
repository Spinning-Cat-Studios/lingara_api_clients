package com.getlingara.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.client.ApiException;
import com.getlingara.client.ApiResponse;
import com.getlingara.client.EventStream;
import com.getlingara.client.LingaraClient;
import com.getlingara.client.MaintenanceException;
import com.getlingara.client.MintedToken;
import com.getlingara.client.OAuthException;
import com.getlingara.client.TransportException;
import com.getlingara.client.model.DialogueTurnRequest;
import com.getlingara.client.model.EmbedTokenRequest;
import com.getlingara.client.model.LessonPlanCreateRequest;
import com.getlingara.client.model.TutorTurnRequest;
import com.getlingara.client.model.VocabRequest;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/** Runs one step's call, n times at once for {@code parallel: n}, and observes each run. */
final class Observe {
  private Observe() {}

  /** One call as the harness saw it, in the contract's vocabulary. */
  static final class Seen {
    String outcome;
    Integer status;
    JsonNode body;
    List<JsonNode> events = new ArrayList<>();
    String variant;
    ObjectNode fields;
    String servedVersion;
    List<Long> sleeps = List.of();
    List<JsonNode> hooks = List.of();
    // An events or tail step's yield: each envelope's id, the UnknownEvent types, the cursor.
    final List<String> eventIds = new ArrayList<>();
    final List<String> unknownTypes = new ArrayList<>();
    String cursor;
    // Every rendering of the client, of a completed call's result, and of a raised error.
    final List<String> renderings = new ArrayList<>();
  }

  static List<String> runStep(Rig rig, JsonNode call, JsonNode expect) {
    rig.reset();
    int n = call.path("parallel").asInt(1);
    List<Seen> runs = parallel(n, () -> invoke(rig, call));
    String operation = call.path("operation").asText();
    List<String> mismatches = new ArrayList<>();
    for (int i = 0; i < runs.size(); i++) {
      Seen seen = runs.get(i);
      seen.sleeps = rig.sleepsSeconds();
      seen.hooks = rig.hookCalls();
      seen.renderings.add(rig.client.toString());
      String label = n > 1 ? "call " + (i + 1) + ": " : "";
      Compare.compare(expect, seen).forEach(m -> mismatches.add(operation + ": " + label + m));
    }
    return mismatches;
  }

  /** n threads released together by one latch, so all n are in flight before any completes. */
  private static List<Seen> parallel(int n, Supplier<Seen> run) {
    Seen[] results = new Seen[n];
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int index = i;
      Thread t = new Thread(() -> results[index] = awaitThen(start, run));
      threads.add(t);
      t.start();
    }
    start.countDown();
    for (Thread t : threads) {
      try {
        t.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for a parallel call", e);
      }
    }
    return List.of(results);
  }

  private static Seen awaitThen(CountDownLatch start, Supplier<Seen> run) {
    try {
      start.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return run.get();
  }

  private static Seen invoke(Rig rig, JsonNode call) {
    LingaraClient c = rig.client;
    String id = call.path("params").path("id").asText(null);
    int cancelAfter = call.path("cancel_after_events").asInt(-1);
    switch (call.path("operation").asText()) {
      case "generateVocabulary":
        return consume(() -> c.generateVocabulary(body(call, VocabRequest.class)), cancelAfter);
      case "createLessonPlan":
        return consume(
            () -> c.createLessonPlan(body(call, LessonPlanCreateRequest.class)), cancelAfter);
      case "streamLessonPlan":
        return consume(() -> c.streamLessonPlan(id), cancelAfter);
      case "sendTutorMessage":
        return consume(() -> c.sendTutorMessage(body(call, TutorTurnRequest.class)), cancelAfter);
      case "sendDialogueTurn":
        return consume(
            () -> c.sendDialogueTurn(body(call, DialogueTurnRequest.class)), cancelAfter);
      case "streamEvents":
        return consume(() -> c.streamEvents(EventSteps.request(call.path("params"))), cancelAfter);
      default:
        return invokeJson(c, call, id);
    }
  }

  private static Seen invokeJson(LingaraClient c, JsonNode call, String id) {
    String operation = call.path("operation").asText();
    switch (operation) {
      case "getLessonPlan":
        return result(() -> c.getLessonPlan(id));
      case "getUsage":
        return result(c::getUsage);
      case "getOpenApiDocument":
        return result(c::getOpenApiDocument);
      case "listApiVersions":
        return result(c::listApiVersions);
      case "getApiVersion":
        return result(() -> c.getApiVersion(id));
      case "getAsyncApiDocument":
        return result(c::getAsyncApiDocument);
      case "listEvents":
        return result(() -> c.listEvents(EventSteps.request(call.path("params"))));
      case "sendEvent":
        // sendEvent's success is a 202, and ApiResponse carries no status.
        Seen sent = result(() -> EventSteps.send(c, call));
        sent.status = sent.status == null ? null : 202;
        return sent;
      case "createEmbedToken":
        return result(() -> c.createEmbedToken(body(call, EmbedTokenRequest.class)));
      case "deleteEmbedPlayer":
        // deleteEmbedPlayer's success is a 204 with no body (ADR 1.10.26w D4).
        Seen deleted =
            result(() -> c.deleteEmbedPlayer(call.path("params").path("player_ref").asText()));
        deleted.status = deleted.status == null ? null : 204;
        return deleted;
      default:
        Seen seen = new Seen();
        seen.outcome = "harness: no operation " + operation;
        return seen;
    }
  }

  private static <T> T body(JsonNode call, Class<T> type) {
    return Harness.JSON.convertValue(call.path("body"), type);
  }

  private static Seen result(Supplier<ApiResponse<?>> call) {
    ApiResponse<?> response;
    try {
      response = call.get();
    } catch (RuntimeException e) {
      return failed(e, List.of(), Optional.empty());
    }
    Seen seen = new Seen();
    seen.outcome = "completed";
    seen.status = 200;
    seen.body =
        response.body() instanceof MintedToken minted
            ? wire(minted)
            : Harness.JSON.valueToTree(response.body());
    seen.servedVersion = response.servedVersion().orElse(null);
    // The result's renderings join the redacted scan (ADR 1.10.26w D7).
    seen.renderings.add(String.valueOf(response));
    seen.renderings.add(String.valueOf(response.body()));
    return seen;
  }

  /** A minted token in wire form, read through its one exposing accessor (ADR 1.10.26w D8). */
  private static JsonNode wire(MintedToken minted) {
    ObjectNode out = Harness.JSON.createObjectNode();
    out.put("token", minted.token().exposeSecret());
    out.put("expires_at", minted.expiresAt());
    out.put("expires_in", minted.expiresIn().getSeconds());
    out.put("subject", minted.subject());
    minted.scopes().forEach(out.putArray("scopes")::add);
    out.put("account_linked", minted.accountLinked());
    return out;
  }

  /** Drains a stream; after {@code cancelAfter} events it closes it, Java's cancellation. */
  private static Seen consume(Supplier<EventStream<?>> open, int cancelAfter) {
    EventStream<?> stream;
    try {
      stream = open.get();
    } catch (RuntimeException e) {
      return failed(e, List.of(), Optional.empty());
    }
    Seen seen = new Seen();
    seen.servedVersion = stream.servedVersion().orElse(null);
    try (stream) {
      for (Object event : stream) {
        seen.events.add(event(event));
        if (seen.events.size() == cancelAfter) {
          stream.close();
          seen.outcome = "cancelled";
          return seen;
        }
      }
    } catch (RuntimeException e) {
      return failed(e, seen.events, stream.servedVersion());
    }
    seen.outcome = "completed";
    seen.status = 200;
    return seen;
  }

  /** A union member as {@code {event, data}}: the record's name, snake-cased, and its payload. */
  private static JsonNode event(Object event) {
    ObjectNode out = Harness.JSON.createObjectNode();
    String name = event.getClass().getSimpleName();
    out.put("event", name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT));
    RecordComponent data = event.getClass().getRecordComponents()[0];
    try {
      out.set("data", Harness.JSON.valueToTree(data.getAccessor().invoke(event)));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  static Seen failed(RuntimeException e, List<JsonNode> events, Optional<String> served) {
    Seen seen = new Seen();
    seen.outcome = e instanceof CancellationException ? "cancelled" : "error";
    seen.events = new ArrayList<>(events);
    seen.servedVersion = served.orElse(null);
    Fields.of(e, seen);
    for (Throwable t = e; t != null; t = t.getCause()) {
      seen.renderings.add(String.valueOf(t));
      seen.renderings.add(String.valueOf(t.getMessage()));
    }
    return seen;
  }

  /** The contract's variant name and snake_case fields for a raised exception. */
  private static final class Fields {
    private Fields() {}

    static void of(RuntimeException e, Seen seen) {
      ObjectNode f = Harness.JSON.createObjectNode();
      seen.fields = f;
      if (e instanceof ApiException a) {
        seen.variant = "ApiError";
        f.put("status", a.status()).put("code", a.code()).put("message", a.getMessage());
        f.put("retry_after", seconds(a.retryAfter()));
        f.put("plan_id", a.planId().orElse(null));
        f.put("served_version", a.servedVersion().orElse(null));
      } else if (e instanceof OAuthException o) {
        seen.variant = "OAuthError";
        f.put("status", o.status()).put("error", o.error());
        f.put("description", o.description().orElse(null));
        f.put("retry_after", seconds(o.retryAfter()));
      } else if (e instanceof MaintenanceException m) {
        seen.variant = "MaintenanceError";
        f.put("body", m.body()).put("retry_after", seconds(m.retryAfter()));
      } else if (e instanceof TransportException t) {
        seen.variant = "TransportError";
        f.put("kind", t.kind().wireName());
      } else if (!(e instanceof CancellationException)) {
        seen.variant = "not a known variant";
        f.put("debug", String.valueOf(e));
      }
    }

    private static Long seconds(Optional<Duration> wait) {
      return wait.map(Duration::getSeconds).orElse(null);
    }
  }
}
