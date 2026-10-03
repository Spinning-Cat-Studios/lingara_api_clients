package com.getlingara.codegen;

import com.getlingara.codegen.EventsCodegen.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * EventsCodegen's Java emitter (ADR 30.9.26aa D3, D10): {@code Event} and one record per arm,
 * {@code UnknownEvent}, {@code InboundEvent} and {@code EventParser}, in {@code
 * com.getlingara.client.events}. Every payload type is written fully qualified, so an arm can never
 * shadow a model of the same simple name.
 */
final class JavaEventsSource {
  private static final String JSON_NODE = "com.fasterxml.jackson.databind.JsonNode";
  private static final String ENVELOPE =
      "String id, String createdAt, String apiVersion, String subject";

  private JavaEventsSource() {}

  /**
   * One file to write.
   *
   * @param name the file name
   * @param text its source
   */
  record File(String name, String text) {}

  static List<File> files(List<Entry> entries, UnaryOperator<String> classOf) {
    List<Entry> out = entries.stream().filter(Entry::outbound).toList();
    List<File> files = new ArrayList<>();
    files.add(new File("Event.java", event(out)));
    for (Entry e : out) {
      files.add(new File(e.arm() + ".java", arm(e, classOf)));
    }
    files.add(new File("UnknownEvent.java", unknown()));
    List<Entry> in = entries.stream().filter(e -> !e.outbound()).toList();
    files.add(new File("InboundEvent.java", inbound(in, classOf)));
    files.add(new File("EventParser.java", EventParserSource.render(out, classOf)));
    return files;
  }

  private static StringBuilder start() {
    return new StringBuilder(EventsCodegen.HEADER)
        .append("\npackage ")
        .append(EventsCodegen.JAVA_PACKAGE)
        .append(";\n\n");
  }

  static String event(List<Entry> out) {
    StringBuilder b = start();
    b.append("/**\n * One event Lingara sends, by webhook, feed or stream (ADR 30.9.26aa D3): one");
    b.append(" record per type\n * in the catalogue this library was generated from, and {@link");
    b.append(" UnknownEvent} for a type\n * added since, which a receiver still acknowledges.\n");
    b.append(" *\n * <p>{@code apiVersion()} is the version {@code data} was rendered at: pin the");
    b.append(" client to\n * {@code LingaraClient.GENERATED_FOR_VERSION}.\n */\n");
    b.append("public sealed interface Event\n    permits ");
    List<String> permits = new ArrayList<>();
    out.forEach(e -> permits.add(e.arm()));
    permits.add("UnknownEvent");
    b.append(String.join(",\n        ", permits)).append(" {\n");
    accessor(b, "id", "the event's id, {@code lgr_evt_…}: the receiver's deduplication key");
    accessor(b, "type", "the event's wire type, such as {@code lesson_plan.ready}");
    accessor(b, "createdAt", "when the event happened, an RFC 3339 timestamp");
    accessor(b, "apiVersion", "the API version {@code data} was rendered at");
    accessor(b, "subject", "the subject the event is about");
    b.append(PARSE);
    return b.append("}\n").toString();
  }

  private static void accessor(StringBuilder b, String name, String doc) {
    b.append("  /**\n   * Returns ").append(doc).append(".\n   *\n   * @return the ");
    b.append(name).append("\n   */\n  String ").append(name).append("();\n\n");
  }

  static String arm(Entry e, UnaryOperator<String> classOf) {
    StringBuilder b = start();
    b.append("/**\n * The {@code ").append(e.type()).append("} event.\n *\n");
    b.append(" * @param id the event's id\n * @param createdAt when it happened\n");
    b.append(" * @param apiVersion the version {@code data} was rendered at\n");
    b.append(" * @param subject the subject it is about\n * @param data its payload\n */\n");
    b.append("public record ").append(e.arm()).append("(\n    ").append(ENVELOPE).append(", ");
    b.append(classOf.apply(e.data())).append(" data)\n    implements Event {\n");
    b.append("  /** The wire type. */\n  public static final String TYPE = \"");
    b.append(e.type()).append("\";\n\n  @Override\n  public String type() {\n");
    b.append("    return TYPE;\n  }\n}\n");
    return b.toString();
  }

  static String unknown() {
    StringBuilder b = start();
    b.append("/**\n * An event whose type this library does not know: the catalogue is additive,");
    b.append(" so a newer\n * type arrives as this, never as an error. Acknowledge it, so the");
    b.append(" deliverer stops\n * retrying, and log it.\n *\n");
    b.append(" * @param id the event's id\n * @param type its wire type\n");
    b.append(" * @param createdAt when it happened\n");
    b.append(" * @param apiVersion the version {@code data} was rendered at\n");
    b.append(" * @param subject the subject it is about\n * @param data its payload, as JSON\n */\n");
    b.append("public record UnknownEvent(\n    String id,\n    String type,\n");
    b.append("    String createdAt,\n    String apiVersion,\n    String subject,\n    ");
    b.append(JSON_NODE).append(" data)\n    implements Event {}\n");
    return b.toString();
  }

  static String inbound(List<Entry> in, UnaryOperator<String> classOf) {
    StringBuilder b = start();
    b.append("/**\n * An event a client sends with {@code sendEvent} (ADR 30.9.26aa D3, D8): one");
    b.append(" factory per\n * inbound type, named after the model it takes. It is sent as {@code");
    b.append(" {type, data}}.\n */\n");
    b.append("public final class InboundEvent {\n  private final String type;\n");
    b.append("  private final Object data;\n\n");
    b.append("  private InboundEvent(String type, Object data) {\n    this.type = type;\n");
    b.append("    this.data = java.util.Objects.requireNonNull(data, \"data\");\n  }\n");
    for (Entry e : in) {
      String model = classOf.apply(e.data());
      b.append("\n  /**\n   * A {@code ").append(e.type()).append("} event.\n   *\n");
      b.append("   * @param data the event's data\n   * @return the event\n   */\n");
      b.append("  public static InboundEvent ").append(lowerFirst(e.data())).append('(');
      b.append(model).append(" data) {\n    return new InboundEvent(\"").append(e.type());
      b.append("\", data);\n  }\n");
    }
    b.append(INBOUND_ACCESSORS);
    return b.append("}\n").toString();
  }

  static String lowerFirst(String name) {
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  private static final String PARSE =
      "  /**\n"
          + "   * Parses one event envelope. A known type decodes into its record; an unknown type"
          + " is an\n"
          + "   * {@link UnknownEvent}, never an error.\n"
          + "   *\n"
          + "   * @param json the envelope's JSON text\n"
          + "   * @return the event\n"
          + "   * @throws IllegalArgumentException for text that is not JSON, not an envelope, or"
          + " a known\n"
          + "   *     type whose data does not decode\n"
          + "   */\n"
          + "  static Event parse(String json) {\n"
          + "    return EventParser.parse(json);\n"
          + "  }\n\n"
          + "  /**\n"
          + "   * Parses one event envelope already parsed into a tree.\n"
          + "   *\n"
          + "   * @param envelope the envelope\n"
          + "   * @return the event\n"
          + "   * @throws IllegalArgumentException for a value that is not an envelope, or a known"
          + " type\n"
          + "   *     whose data does not decode\n"
          + "   */\n"
          + "  static Event parse("
          + JSON_NODE
          + " envelope) {\n"
          + "    return EventParser.parse(envelope);\n"
          + "  }\n";

  /** EventParser.java's source: {@code type} first, then a known type's data into its arm. */
  static final class EventParserSource {
    private EventParserSource() {}

    static String render(List<Entry> out, UnaryOperator<String> classOf) {
      StringBuilder b = start();
      b.append(PARSER_IMPORTS).append(PARSER_PREAMBLE);
      b.append("    switch (type) {\n");
      for (Entry e : out) {
        String model = classOf.apply(e.data());
        b.append("      case ").append(e.arm()).append(".TYPE:\n");
        b.append("        return new ").append(e.arm());
        b.append("(id, createdAt, apiVersion, subject, ");
        if (model.equals(JSON_NODE)) {
          b.append("data);\n");
        } else {
          b.append("decode(data, ").append(model).append(".class));\n");
        }
      }
      b.append("      default:\n");
      b.append("        return new UnknownEvent(id, type, createdAt, apiVersion, subject, data);\n");
      b.append("    }\n  }\n").append(PARSER_HELPERS);
      return b.append("}\n").toString();
    }

    private static final String PARSER_IMPORTS =
        "import com.fasterxml.jackson.core.JsonProcessingException;\n"
            + "import com.fasterxml.jackson.databind.DeserializationFeature;\n"
            + "import com.fasterxml.jackson.databind.JsonNode;\n"
            + "import com.fasterxml.jackson.databind.ObjectMapper;\n"
            + "import com.fasterxml.jackson.databind.cfg.CoercionAction;\n"
            + "import com.fasterxml.jackson.databind.cfg.CoercionInputShape;\n"
            + "import com.fasterxml.jackson.databind.type.LogicalType;\n\n";

    private static final String PARSER_PREAMBLE =
        "/**\n"
            + " * {@link Event#parse}: reads {@code type} first, then decodes a known type's data"
            + " into its\n"
            + " * record (ADR 30.9.26aa D3). An unknown type is an {@link UnknownEvent}.\n"
            + " */\n"
            + "final class EventParser {\n"
            + "  private static final ObjectMapper MAPPER = mapper();\n\n"
            + "  private EventParser() {}\n\n"
            + "  /**\n"
            + "   * The parser's mapper: an unknown field is ignored, since fields are additive, but"
            + " a JSON\n"
            + "   * number or boolean is never read into a string field, so data of the wrong"
            + " shape does\n"
            + "   * not decode.\n"
            + "   */\n"
            + "  private static ObjectMapper mapper() {\n"
            + "    ObjectMapper mapper =\n"
            + "        new ObjectMapper()"
            + ".configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);\n"
            + "    mapper\n"
            + "        .coercionConfigFor(LogicalType.Textual)\n"
            + "        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)\n"
            + "        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)\n"
            + "        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);\n"
            + "    return mapper;\n"
            + "  }\n\n"
            + "  static Event parse(String json) {\n"
            + "    JsonNode envelope;\n"
            + "    try {\n"
            + "      envelope = MAPPER.readTree(json);\n"
            + "    } catch (JsonProcessingException e) {\n"
            + "      throw new IllegalArgumentException(\"the event is not JSON\", e);\n"
            + "    }\n"
            + "    return parse(envelope);\n"
            + "  }\n\n"
            + "  static Event parse(JsonNode envelope) {\n"
            + "    String id = text(envelope, \"id\");\n"
            + "    String type = text(envelope, \"type\");\n"
            + "    String createdAt = text(envelope, \"created_at\");\n"
            + "    String apiVersion = text(envelope, \"api_version\");\n"
            + "    String subject = text(envelope, \"subject\");\n"
            + "    JsonNode data = envelope == null ? null : envelope.get(\"data\");\n"
            + "    if (id == null\n"
            + "        || type == null\n"
            + "        || createdAt == null\n"
            + "        || apiVersion == null\n"
            + "        || subject == null\n"
            + "        || data == null\n"
            + "        || !data.isObject()) {\n"
            + "      throw new IllegalArgumentException(\"the value is not an event envelope\");\n"
            + "    }\n";

    private static final String PARSER_HELPERS =
        "\n  private static String text(JsonNode envelope, String field) {\n"
            + "    JsonNode value = envelope == null ? null : envelope.get(field);\n"
            + "    return value != null && value.isTextual() ? value.asText() : null;\n"
            + "  }\n\n"
            + "  private static <T> T decode(JsonNode data, Class<T> type) {\n"
            + "    try {\n"
            + "      return MAPPER.treeToValue(data, type);\n"
            + "    } catch (JsonProcessingException e) {\n"
            + "      throw new IllegalArgumentException(\"the event's data does not decode\", e);\n"
            + "    }\n"
            + "  }\n";
  }

  private static final String INBOUND_ACCESSORS =
      "\n  /**\n"
          + "   * Returns the wire type, such as {@code world.context_changed}.\n"
          + "   *\n"
          + "   * @return the type\n"
          + "   */\n"
          + "  public String type() {\n"
          + "    return type;\n"
          + "  }\n\n"
          + "  /**\n"
          + "   * Returns the event's data: the model its factory took.\n"
          + "   *\n"
          + "   * @return the data\n"
          + "   */\n"
          + "  public Object data() {\n"
          + "    return data;\n"
          + "  }\n\n"
          + "  @Override\n"
          + "  public String toString() {\n"
          + "    return \"InboundEvent{type=\" + type + \", data=\" + data + \"}\";\n"
          + "  }\n";
}
