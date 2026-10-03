package com.getlingara.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.client.ApiResponse;
import com.getlingara.client.EventStream;
import com.getlingara.client.LingaraClient;
import com.getlingara.client.events.Event;
import com.getlingara.client.events.EventFeed;
import com.getlingara.client.events.EventsRequest;
import com.getlingara.client.events.InboundEvent;
import com.getlingara.client.events.SendEventOptions;
import com.getlingara.client.events.UnknownEvent;
import com.getlingara.client.model.InboundEventAccepted;
import com.getlingara.client.model.WorldContextChanged;
import com.getlingara.client.model.WorldPracticeRequested;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The event helpers' steps (ADR 30.9.26aa D9): {@code events} iterates {@code client.events(…)} to
 * its end, {@code tail} takes {@code take} events from {@code client.tailEvents(…)} and then stops
 * it, and {@code sendEvent} builds its {@link InboundEvent} from the case's {@code {type, data}}
 * through the public API.
 */
final class EventSteps {
  private EventSteps() {}

  /** One {@code events} or {@code tail} step, observed and compared. */
  static List<String> runStep(Rig rig, String kind, JsonNode step, JsonNode expect) {
    rig.reset();
    Observe.Seen seen = kind.equals("events") ? events(rig.client, step) : tail(rig.client, step);
    seen.sleeps = rig.sleepsSeconds();
    seen.hooks = rig.hookCalls();
    seen.renderings.add(rig.client.toString());
    List<String> mismatches = new ArrayList<>();
    Compare.compare(expect, seen).forEach(m -> mismatches.add(kind + ": " + m));
    return mismatches;
  }

  /** The step's (or a call's params') {@code cursor}, {@code start}, {@code types} and limit. */
  static EventsRequest request(JsonNode params) {
    EventsRequest request = EventsRequest.of();
    if (params.path("cursor").isTextual()) {
      request = request.cursor(params.path("cursor").asText());
    }
    if (params.path("start").isTextual()) {
      request = request.start(params.path("start").asText());
    }
    List<String> types = new ArrayList<>();
    params.path("types").forEach(t -> types.add(t.asText()));
    request = request.types(types.toArray(String[]::new));
    return params.path("limit").isNumber() ? request.limit(params.path("limit").asInt()) : request;
  }

  /** {@code sendEvent} with the case's body as an {@link InboundEvent}, and its key if any. */
  static ApiResponse<InboundEventAccepted> send(LingaraClient c, JsonNode call) {
    JsonNode body = call.path("body");
    JsonNode data = body.path("data");
    InboundEvent event =
        switch (body.path("type").asText()) {
          case "world.context_changed" ->
              InboundEvent.worldContextChanged(
                  Harness.JSON.convertValue(data, WorldContextChanged.class));
          case "world.practice_requested" ->
              InboundEvent.worldPracticeRequested(
                  Harness.JSON.convertValue(data, WorldPracticeRequested.class));
          default -> throw new IllegalArgumentException("no inbound type " + body.path("type"));
        };
    return c.sendEvent(event, new SendEventOptions(call.path("idempotency_key").asText(null)));
  }

  private static Observe.Seen events(LingaraClient c, JsonNode step) {
    EventFeed feed = c.events(request(step));
    Observe.Seen seen = new Observe.Seen();
    try {
      while (feed.hasNext()) {
        record(seen, feed.next());
      }
    } catch (RuntimeException e) {
      return failed(e, seen, feed.cursor());
    }
    seen.outcome = "completed";
    seen.cursor = feed.cursor().orElse(null);
    return seen;
  }

  /** Takes {@code take} events, then closes the tail: the outcome is then {@code completed}. */
  private static Observe.Seen tail(LingaraClient c, JsonNode step) {
    int take = step.path("take").asInt();
    Observe.Seen seen = new Observe.Seen();
    EventStream<Event> tail = c.tailEvents(request(step));
    try (tail) {
      for (Event event : tail) {
        record(seen, event);
        if (seen.eventIds.size() == take) {
          break;
        }
      }
    } catch (RuntimeException e) {
      return failed(e, seen, tail.cursor());
    }
    seen.outcome = "completed";
    seen.cursor = tail.cursor().orElse(null);
    return seen;
  }

  private static void record(Observe.Seen seen, Event event) {
    seen.eventIds.add(event.id());
    if (event instanceof UnknownEvent) {
      seen.unknownTypes.add(event.type());
    }
  }

  private static Observe.Seen failed(
      RuntimeException e, Observe.Seen before, Optional<String> cursor) {
    Observe.Seen seen = Observe.failed(e, List.of(), Optional.empty());
    seen.eventIds.addAll(before.eventIds);
    seen.unknownTypes.addAll(before.unknownTypes);
    seen.cursor = cursor.orElse(null);
    return seen;
  }
}
