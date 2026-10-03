package com.getlingara.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventsCodegenTest {
  private static final String MODEL = "com/getlingara/client/model/";
  private static final String KOTLIN_MODEL = "com/getlingara/kotlin/model/";

  @TempDir Path sources;

  private static JsonNode view() throws Exception {
    return new ObjectMapper().readTree(Path.of(System.getProperty("lingara.view")).toFile());
  }

  /** Stands in for openapi-generator's output: an empty file per data model but WebhookTestData. */
  private void stageModels(String dir, String extension, List<EventsCodegen.Entry> entries)
      throws Exception {
    Files.createDirectories(sources.resolve(dir));
    for (EventsCodegen.Entry e : entries) {
      if (!e.data().equals("WebhookTestData")) {
        Files.writeString(sources.resolve(dir + e.data() + extension), "");
      }
    }
  }

  /**
   * ADR 30.9.26aa D3: over the committed 3.0 view, the Java emitter writes one record per outbound
   * entry, UnknownEvent, an InboundEvent factory per inbound entry named after its model, and a
   * free-form payload as JsonNode; it refuses a staged model named after an arm.
   */
  @Test
  void javaEmitterWritesTheUnionAndRefusesAGeneratorCopy() throws Exception {
    EventsCodegen codegen = new EventsCodegen(view(), sources, StreamsCodegen.Lang.JAVA);
    List<EventsCodegen.Entry> entries = codegen.entries();
    assertEquals(6, entries.stream().filter(EventsCodegen.Entry::outbound).count());
    stageModels(MODEL, ".java", entries);
    codegen.write();
    Path events = sources.resolve("com/getlingara/client/events/");
    String event = Files.readString(events.resolve("Event.java"));
    assertTrue(event.contains("public sealed interface Event"), event);
    assertTrue(event.contains("UnknownEvent {"), event);
    String ready = Files.readString(events.resolve("LessonPlanReady.java"));
    assertTrue(ready.contains("com.getlingara.client.model.LessonPlanReadyData data)"), ready);
    assertTrue(ready.contains("TYPE = \"lesson_plan.ready\""), ready);
    String test = Files.readString(events.resolve("WebhookTest.java"));
    assertTrue(test.contains("com.fasterxml.jackson.databind.JsonNode data)"), test);
    String inbound = Files.readString(events.resolve("InboundEvent.java"));
    assertTrue(inbound.contains("public static InboundEvent worldContextChanged("), inbound);
    Path fresh = Files.createTempDirectory("staged");
    Files.createDirectories(fresh.resolve(MODEL));
    Files.writeString(fresh.resolve(MODEL + "LessonPlanReady.java"), "");
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new EventsCodegen(view(), fresh, StreamsCodegen.Lang.JAVA).write());
    assertTrue(e.getMessage().contains("LessonPlanReady.java"), e.getMessage());
  }

  /**
   * ADR 30.9.26aa D3: the Kotlin emitter writes the same union as data classes, InboundEvent as a
   * sealed interface with a class per inbound entry, and refuses a staged arm.
   */
  @Test
  void kotlinEmitterWritesTheUnionAndRefusesAGeneratorCopy() throws Exception {
    EventsCodegen codegen = new EventsCodegen(view(), sources, StreamsCodegen.Lang.KOTLIN);
    stageModels(KOTLIN_MODEL, ".kt", codegen.entries());
    codegen.write();
    Path events = sources.resolve("com/getlingara/kotlin/events/");
    String ready = Files.readString(events.resolve("LessonPlanReady.kt"));
    assertTrue(ready.contains("public val data: com.getlingara.kotlin.model.LessonPlanReadyData"));
    String inbound = Files.readString(events.resolve("InboundEvent.kt"));
    assertTrue(inbound.contains("public data class WorldPracticeRequested("), inbound);
    String parser = Files.readString(events.resolve("EventParser.kt"));
    assertTrue(parser.contains("else -> UnknownEvent("), parser);
    Path fresh = Files.createTempDirectory("staged");
    Files.createDirectories(fresh.resolve(KOTLIN_MODEL));
    Files.writeString(fresh.resolve(KOTLIN_MODEL + "AppInstalled.kt"), "");
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new EventsCodegen(view(), fresh, StreamsCodegen.Lang.KOTLIN).write());
    assertTrue(e.getMessage().contains("kotlin.openapi-generator-ignore"), e.getMessage());
  }
}
