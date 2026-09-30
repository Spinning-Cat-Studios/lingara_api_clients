package com.getlingara.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamsCodegenTest {
  private static final String MODEL = "com/getlingara/client/model/";

  @TempDir Path sources;
  @TempDir Path resources;

  private static JsonNode view() throws Exception {
    return new ObjectMapper().readTree(Path.of(System.getProperty("lingara.view")).toFile());
  }

  /** Stands in for openapi-generator's output: an empty file per payload model. */
  private void stageModels(List<StreamsCodegen.Stream> streams) throws Exception {
    Files.createDirectories(sources.resolve(MODEL));
    for (StreamsCodegen.Stream s : streams) {
      for (StreamsCodegen.Event e : s.events()) {
        if (!e.data().equals("Done")) {
          Files.writeString(sources.resolve(MODEL + e.data() + ".java"), "");
        }
      }
    }
  }

  private String read(String file) throws Exception {
    return Files.readString(sources.resolve(MODEL + file));
  }

  /**
   * 29.9.26r AC24: over the committed 3.0 view, StreamsCodegen emits exactly the four sealed unions
   * with one record per event other than done and error, each holding its branch's data type, and
   * it refuses an output directory holding a file named after a union or a branch.
   */
  @Test
  void emitsTheFourUnionsAndRefusesAGeneratorCopy() throws Exception {
    StreamsCodegen codegen = new StreamsCodegen(view(), sources);
    List<StreamsCodegen.Stream> streams = codegen.streams();
    assertEquals(4, streams.size());
    stageModels(streams);
    codegen.write("1.2.3", resources);
    String vocab = read("GenerateVocabularyEvent.java");
    assertTrue(vocab.contains("public sealed interface GenerateVocabularyEvent"));
    assertTrue(vocab.contains("record Started(com.getlingara.client.model.VocabStarted data)"));
    assertTrue(vocab.contains("record Item(com.getlingara.client.model.VocabItem data)"));
    assertFalse(vocab.contains("record Done"));
    assertFalse(vocab.contains("record Error"));
    assertTrue(read("StreamLessonPlanEvent.java").contains("record Pending("));
    assertTrue(read("CreateLessonPlanEvent.java").contains("record Result("));
    assertTrue(
        read("SendTutorMessageEvent.java")
            .contains("record Notice(com.getlingara.client.model.Notice data)"));
    assertEquals(
        "version=1.2.3\n",
        Files.readString(resources.resolve("com/getlingara/client/version.properties")));
    for (String copy : List.of("GenerateVocabularyEvent.java", "SendTutorMessageEventDelta.java")) {
      Path fresh = Files.createTempDirectory("staged");
      Files.createDirectories(fresh.resolve(MODEL));
      Files.writeString(fresh.resolve(MODEL + copy), "");
      IllegalStateException e =
          assertThrows(
              IllegalStateException.class,
              () -> new StreamsCodegen(view(), fresh).write("1", resources));
      assertTrue(e.getMessage().contains(copy), e.getMessage());
    }
  }

  private static final String KOTLIN_MODEL = "com/getlingara/kotlin/model/";

  /** Stands in for openapi-generator's Kotlin output: an empty file per payload model. */
  private void stageKotlinModels(Path root, List<StreamsCodegen.Stream> streams) throws Exception {
    Files.createDirectories(root.resolve(KOTLIN_MODEL));
    for (StreamsCodegen.Stream s : streams) {
      for (StreamsCodegen.Event e : s.events()) {
        if (!e.data().equals("Done")) {
          Files.writeString(root.resolve(KOTLIN_MODEL + e.data() + ".kt"), "");
        }
      }
    }
  }

  /**
   * 29.9.26s AC26: over the committed 3.0 view, StreamsCodegen's Kotlin emitter writes exactly the
   * four sealed unions with one Serializable data class per event other than done and error, each
   * holding its branch's data type, and it refuses a staging directory holding a file named after a
   * union or a branch.
   */
  @Test
  void kotlinEmitterWritesTheFourUnionsAndRefusesAGeneratorCopy() throws Exception {
    StreamsCodegen codegen = new StreamsCodegen(view(), sources, StreamsCodegen.Lang.KOTLIN);
    List<StreamsCodegen.Stream> streams = codegen.streams();
    assertEquals(4, streams.size());
    stageKotlinModels(sources, streams);
    codegen.writeKotlin("1.2.3");
    try (var unions = Files.list(sources.resolve(KOTLIN_MODEL))) {
      assertEquals(4, unions.filter(p -> p.toString().endsWith("Event.kt")).count());
    }
    String vocab = Files.readString(sources.resolve(KOTLIN_MODEL + "GenerateVocabularyEvent.kt"));
    assertTrue(vocab.contains("public sealed interface GenerateVocabularyEvent {"));
    assertTrue(
        vocab.contains(
            "@Serializable\n    public data class Started(public val data:"
                + " com.getlingara.kotlin.model.VocabStarted) : GenerateVocabularyEvent"));
    assertTrue(vocab.contains("public data class Item(public val data:"));
    assertFalse(vocab.contains("class Done"));
    assertFalse(vocab.contains("class Error"));
    String pending = Files.readString(sources.resolve(KOTLIN_MODEL + "StreamLessonPlanEvent.kt"));
    assertTrue(pending.contains("class Pending(public val data: com.getlingara.kotlin.model."));
    String tutor = Files.readString(sources.resolve(KOTLIN_MODEL + "SendTutorMessageEvent.kt"));
    assertTrue(tutor.contains("class Notice(public val data: com.getlingara.kotlin.model.Notice)"));
    Path internal = sources.resolve("com/getlingara/kotlin/internal/");
    assertTrue(
        Files.readString(internal.resolve("BuildInfo.kt")).contains("VERSION: String = \"1.2.3\""));
    assertTrue(
        Files.readString(internal.resolve("Streams.kt")).contains("internal object Streams"));
    for (String copy : List.of("CreateLessonPlanEvent.kt", "StreamLessonPlanEventPending.kt")) {
      Path fresh = Files.createTempDirectory("staged");
      Files.createDirectories(fresh.resolve(KOTLIN_MODEL));
      Files.writeString(fresh.resolve(KOTLIN_MODEL + copy), "");
      IllegalStateException e =
          assertThrows(
              IllegalStateException.class,
              () -> new StreamsCodegen(view(), fresh, StreamsCodegen.Lang.KOTLIN).writeKotlin("1"));
      assertTrue(e.getMessage().contains(copy), e.getMessage());
      assertTrue(e.getMessage().contains("kotlin.openapi-generator-ignore"), e.getMessage());
    }
  }
}
