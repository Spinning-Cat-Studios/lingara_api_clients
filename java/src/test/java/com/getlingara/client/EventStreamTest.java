package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getlingara.client.internal.Streams;
import com.getlingara.client.model.CreateLessonPlanEvent;
import com.getlingara.client.model.GenerateVocabularyEvent;
import com.getlingara.client.model.SendDialogueTurnEvent;
import com.getlingara.client.model.SendTutorMessageEvent;
import com.getlingara.client.model.StreamLessonPlanEvent;
import com.getlingara.client.model.VocabRequest;
import java.io.File;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EventStreamTest {
  static final String STARTED =
      "event: started\ndata: {\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\","
          + "\"framework\":\"HSK\",\"count\":2,\"ai_generated\":true}}\n\n";
  static final String ITEM =
      "event: item\ndata: {\"word\":\"你好\",\"pronunciation\":\"nǐ hǎo\",\"translation\":\"hello\"}\n\n";
  static final String DONE = "event: done\ndata: {}\n\n";
  static final String PLAN_STARTED =
      "event: started\ndata: {\"plan_id\":\"3f1c2a9e-5b7d-4e21-9a0c-6d8e4f2b1a37\"}\n\n";
  static final String PHASE = "event: phase\ndata: {\"phase\":\"vocabulary\",\"attempt\":1}\n\n";

  private static final ObjectMapper MAPPER = LingaraClient.mapper();

  private static EventStream<GenerateVocabularyEvent> vocab(InputStream body, Duration idle) {
    return new EventStream<>(
        Streams.GENERATE_VOCABULARY,
        Streams::decodeGenerateVocabulary,
        body,
        idle,
        new EventStream.Settings(Optional.of("2026-09-knowing-tenpounder"), MAPPER));
  }

  private static EventStream<GenerateVocabularyEvent> vocab(InputStream body) {
    return vocab(body, Duration.ofSeconds(120));
  }

  private static <E> List<E> drain(EventStream<E> stream) {
    List<E> out = new ArrayList<>();
    try (stream) {
      stream.forEach(out::add);
    }
    return out;
  }

  /** A server that sends one started frame and then holds, recording when the client hangs up. */
  private static Scripted.Listener holding(AtomicLong hungUpAt, CountDownLatch hungUp)
      throws java.io.IOException {
    return new Scripted.Listener(
        s -> {
          Scripted.Listener.readRequest(s);
          String chunk = STARTED;
          int size = chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
          Scripted.Listener.write(
              s,
              "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                  + "Transfer-Encoding: chunked\r\n\r\n"
                  + Integer.toHexString(size)
                  + "\r\n"
                  + chunk
                  + "\r\n");
          try {
            while (s.getInputStream().read() >= 0) {
              // Hold until the client closes the connection.
            }
          } catch (java.io.IOException e) {
            // A reset is a disconnect too.
          }
          hungUpAt.set(System.nanoTime());
          hungUp.countDown();
        });
  }

  /**
   * 29.9.26r AC12: close() mid-stream closes the connection, which a local listener sees as EOF
   * within 2 s, and a second iterator() throws IllegalStateException.
   */
  @Test
  void closeReleasesTheConnectionAndTheStreamIsSingleUse() throws Exception {
    AtomicLong hungUpAt = new AtomicLong();
    CountDownLatch hungUp = new CountDownLatch(1);
    try (Scripted.Listener server = holding(hungUpAt, hungUp)) {
      LingaraClient client = LingaraClient.builder().baseUrl(server.uri("http")).build();
      EventStream<GenerateVocabularyEvent> stream =
          client.generateVocabulary(new VocabRequest().level(2).sourceLang("en").targetLang("zh"));
      var events = stream.iterator();
      assertInstanceOf(GenerateVocabularyEvent.Started.class, events.next());
      long closedAt = System.nanoTime();
      stream.close();
      assertTrue(hungUp.await(2, TimeUnit.SECONDS), "the server saw no disconnect within 2 s");
      assertTrue(hungUpAt.get() - closedAt < TimeUnit.SECONDS.toNanos(2));
      assertFalse(events.hasNext(), "after close() no further event");
      assertThrows(IllegalStateException.class, stream::iterator);
    }
  }

  /** Iterates on its own thread, recording what hasNext() threw and the interrupt flag. */
  private static Thread reader(
      EventStream<?> stream, AtomicReference<Throwable> thrown, AtomicReference<Boolean> flag) {
    Thread t =
        new Thread(
            () -> {
              try {
                stream.iterator().hasNext();
              } catch (RuntimeException e) {
                thrown.set(e);
                flag.set(Thread.currentThread().isInterrupted());
              }
            });
    t.start();
    return t;
  }

  /**
   * 29.9.26r AC13: an interrupt of the reading thread, and a close() from another thread, both make
   * a blocked hasNext() throw CancellationException (the flag restored after the interrupt), never
   * a LingaraException, whether the underlying read then throws or returns EOF.
   */
  @Test
  void interruptAndForeignCloseAreCancellation() throws Exception {
    for (Scripted.Ending ending : Scripted.Ending.values()) {
      Scripted.Body body = new Scripted.Body(ending, Scripted.Body.BLOCK);
      AtomicReference<Throwable> thrown = new AtomicReference<>();
      AtomicReference<Boolean> flag = new AtomicReference<>();
      Thread t = reader(vocab(body), thrown, flag);
      assertTrue(body.blocked.await(2, TimeUnit.SECONDS));
      t.interrupt();
      t.join(2000);
      assertInstanceOf(CancellationException.class, thrown.get(), ending.name());
      assertTrue(flag.get(), "the interrupt flag is restored");

      Scripted.Body closing = new Scripted.Body(ending, Scripted.Body.BLOCK);
      EventStream<GenerateVocabularyEvent> stream = vocab(closing);
      AtomicReference<Throwable> closed = new AtomicReference<>();
      Thread u = reader(stream, closed, new AtomicReference<>());
      assertTrue(closing.blocked.await(2, TimeUnit.SECONDS));
      stream.close();
      u.join(2000);
      assertInstanceOf(CancellationException.class, closed.get(), ending.name());
    }
  }

  /**
   * 29.9.26r AC14: with streamIdleTimeout at 200 ms, a body silent for 200 ms while a read is
   * pending fails with TIMEOUT whether the closed read throws or returns EOF; a keepalive every 50
   * ms keeps it open; a consumer that holds one event for 400 ms then calls hasNext() receives the
   * next event; and the stream's own watchdog reports itself cancelled after the stream ends.
   */
  @Test
  void idleTimeoutIsAnOptionAndAKeepaliveResetsIt() {
    Duration idle = Duration.ofMillis(200);
    for (Scripted.Ending ending : Scripted.Ending.values()) {
      EventStream<GenerateVocabularyEvent> silent =
          vocab(new Scripted.Body(ending, STARTED, Scripted.Body.BLOCK), idle);
      var events = silent.iterator();
      events.next();
      TransportException e = assertThrows(TransportException.class, events::hasNext);
      assertEquals(TransportKind.TIMEOUT, e.kind(), ending.name());
    }
    List<Object> keptAlive = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      keptAlive.add(50L);
      keptAlive.add(": keepalive\n\n");
    }
    keptAlive.add(STARTED);
    keptAlive.add(DONE);
    EventStream<GenerateVocabularyEvent> alive =
        vocab(new Scripted.Body(keptAlive.toArray()), idle);
    assertEquals(1, drain(alive).size());
    assertTrue(alive.watchdog.isCancelled(), "the watchdog is cancelled when the stream ends");

    EventStream<GenerateVocabularyEvent> held = vocab(new Scripted.Body(STARTED, ITEM, DONE), idle);
    var slow = held.iterator();
    slow.next();
    Fakes.sleep(400);
    assertInstanceOf(GenerateVocabularyEvent.Item.class, slow.next());
    assertFalse(slow.hasNext());
    assertNotNull(held.watchdog);
    assertTrue(held.watchdog.isCancelled());
  }

  private static <E> List<E> run(
      Streams.Route route, EventStream.Decoder<E> decoder, Object... body) {
    return drain(
        new EventStream<>(
            route,
            decoder,
            new Scripted.Body(body),
            Duration.ofSeconds(120),
            new EventStream.Settings(Optional.empty(), MAPPER)));
  }

  /**
   * 29.9.26r AC15: each stream operation ends on its own terminal (result and pending yielded, done
   * not), and Streams' generated terminal table is the view's endsOn: every view stream has a route
   * and a client method, and every terminal is among its route's event names.
   */
  @Test
  void eachOperationEndsOnItsOwnTerminal() throws Exception {
    assertEquals(
        2,
        run(Streams.GENERATE_VOCABULARY, Streams::decodeGenerateVocabulary, STARTED, ITEM, DONE)
            .size());
    List<CreateLessonPlanEvent> created =
        run(
            Streams.CREATE_LESSON_PLAN,
            Streams::decodeCreateLessonPlan,
            PLAN_STARTED,
            PHASE,
            "event: result\ndata: {\"plan\":{}}\n\n",
            Scripted.Body.FAIL);
    assertInstanceOf(CreateLessonPlanEvent.Result.class, created.get(2));
    List<StreamLessonPlanEvent> pending =
        run(
            Streams.STREAM_LESSON_PLAN,
            Streams::decodeStreamLessonPlan,
            PLAN_STARTED,
            "event: pending\ndata: {\"plan_id\":\"p\",\"status\":\"generating\"}\n\n",
            Scripted.Body.FAIL);
    assertInstanceOf(StreamLessonPlanEvent.Pending.class, pending.get(1));
    List<SendTutorMessageEvent> tutor =
        run(
            Streams.SEND_TUTOR_MESSAGE,
            Streams::decodeSendTutorMessage,
            "event: delta\ndata: {\"text\":\"你好\"}\n\n",
            DONE,
            Scripted.Body.FAIL);
    assertEquals(1, tutor.size());
    // 1.10.26w D8: the NPC turn is the tutor's shape; bytes after done are never read.
    String delta = "event: delta\ndata: {\"text\":\"十块钱\"}\n\n";
    List<SendDialogueTurnEvent> dialogue =
        run(Streams.SEND_DIALOGUE_TURN, Streams::decodeSendDialogueTurn, delta, DONE, delta);
    assertEquals(1, dialogue.size());
    assertInstanceOf(SendDialogueTurnEvent.Delta.class, dialogue.get(0));
    assertTerminalTableIsTheView();
  }

  private static void assertTerminalTableIsTheView() throws Exception {
    JsonNode view = MAPPER.readTree(new File(System.getProperty("lingara.view")));
    Set<String> operations = new HashSet<>();
    for (JsonNode entry : view.path("x-lingara-streams")) {
      String id = entry.path("operationId").asText();
      operations.add(id);
      Streams.Route route = Streams.ROUTES.get(id);
      assertNotNull(route, id);
      List<String> events =
          MAPPER.convertValue(
              entry.path("events"),
              MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
      assertEquals(events, route.events(), id);
      Set<String> endsOn =
          new HashSet<>(
              MAPPER.convertValue(
                  entry.path("endsOn"),
                  MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
      assertEquals(endsOn, route.endsOn().keySet(), id);
      assertTrue(route.events().containsAll(route.endsOn().keySet()), id);
      assertEquals(Streams.Ending.RAISE, route.endsOn().get(entry.path("error").asText()), id);
      // streamEvents has neither a body nor a path parameter: its no-argument overload.
      Class<?>[] parameters =
          !route.pathParameters().isEmpty()
              ? new Class<?>[] {String.class}
              : route.requestBody() == null
                  ? new Class<?>[0]
                  : new Class<?>[] {route.requestBody()};
      assertNotNull(LingaraClient.class.getMethod(id, parameters), id);
    }
    assertEquals(operations, Streams.ROUTES.keySet());
  }

  /**
   * 29.9.26r AC16: an error event makes hasNext() throw ApiException with status 200, code,
   * message, planId and servedVersion, and the stream then closes.
   */
  @Test
  void anErrorEventRaisesApiExceptionWithPlanId() {
    Scripted.Body body =
        new Scripted.Body(
            STARTED,
            "event: error\ndata: {\"code\":\"generation_failed\",\"message\":\"It failed.\","
                + "\"plan_id\":\"p-1\"}\n\n",
            Scripted.Body.FAIL);
    var events = vocab(body).iterator();
    events.next();
    ApiException e = assertThrows(ApiException.class, events::hasNext);
    assertEquals(200, e.status());
    assertEquals("generation_failed", e.code());
    assertEquals("It failed.", e.getMessage());
    assertEquals(Optional.of("p-1"), e.planId());
    assertEquals(Optional.of("2026-09-knowing-tenpounder"), e.servedVersion());
    assertTrue(body.closed);
    assertFalse(events.hasNext());
  }

  private static TransportKind failure(Object... body) {
    return assertThrows(TransportException.class, () -> drain(vocab(new Scripted.Body(body))))
        .kind();
  }

  /**
   * 29.9.26r AC17: a non-SSE 200 is MALFORMED_RESPONSE from the call, an unknown event is skipped,
   * a known event with undecodable data is MALFORMED_EVENT, EOF before a terminal is
   * STREAM_ENDED_EARLY, and bytes after a terminal are never read.
   */
  @Test
  void theStreamEndsPerC2D6() throws Exception {
    try (Fakes.Server server =
        new Fakes.Server().on("/v1/vocab/stream", e -> Fakes.json(e, 200, "{\"not\":\"sse\"}"))) {
      LingaraClient client = LingaraClient.builder().baseUrl(server.uri()).build();
      TransportException e =
          assertThrows(
              TransportException.class,
              () -> client.generateVocabulary(new VocabRequest().level(1)));
      assertEquals(TransportKind.MALFORMED_RESPONSE, e.kind());
    }
    assertEquals(
        1, drain(vocab(new Scripted.Body("event: mystery\ndata: {}\n\n", STARTED, DONE))).size());
    assertEquals(TransportKind.MALFORMED_EVENT, failure("event: item\ndata: {not json\n\n"));
    assertEquals(TransportKind.MALFORMED_EVENT, failure("event: item\ndata: \"a string\"\n\n"));
    assertEquals(TransportKind.STREAM_ENDED_EARLY, failure(STARTED));
    Scripted.Body afterDone = new Scripted.Body(STARTED + DONE, Scripted.Body.FAIL);
    assertEquals(1, drain(vocab(afterDone)).size());
    assertFalse(afterDone.readAfterFail);
    assertEquals(1, afterDone.reads.get());
  }
}
