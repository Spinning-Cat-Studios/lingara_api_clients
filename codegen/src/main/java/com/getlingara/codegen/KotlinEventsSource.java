package com.getlingara.codegen;

import com.getlingara.codegen.EventsCodegen.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * EventsCodegen's Kotlin emitter (ADR 30.9.26aa D3, D10): {@code Event} and one data class per
 * arm, {@code UnknownEvent}, {@code InboundEvent} and the internal {@code EventParser}, in {@code
 * com.getlingara.kotlin.events}, from the same reading of the view as the Java emitter. Every
 * payload type is written fully qualified, so an arm or an inbound constructor can never shadow a
 * model of the same simple name.
 */
final class KotlinEventsSource {
  private static final String JSON_ELEMENT = "kotlinx.serialization.json.JsonElement";
  private static final String INDENT = "    ";

  private KotlinEventsSource() {}

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
    files.add(new File("Event.kt", EVENT));
    for (Entry e : out) {
      files.add(new File(e.arm() + ".kt", arm(e, classOf)));
    }
    files.add(new File("UnknownEvent.kt", UNKNOWN));
    List<Entry> in = entries.stream().filter(e -> !e.outbound()).toList();
    files.add(new File("InboundEvent.kt", inbound(in, classOf)));
    files.add(new File("EventParser.kt", parser(out, classOf)));
    return files;
  }

  private static String start(String imports) {
    return EventsCodegen.HEADER + "\npackage " + EventsCodegen.KOTLIN_PACKAGE + "\n\n" + imports;
  }

  static String arm(Entry e, UnaryOperator<String> classOf) {
    StringBuilder b = new StringBuilder(start(""));
    b.append("/** The `").append(e.type()).append("` event. */\n");
    b.append("public data class ").append(e.arm()).append("(\n");
    for (String field : List.of("id", "createdAt", "apiVersion", "subject")) {
      b.append(INDENT).append("override val ").append(field).append(": String,\n");
    }
    b.append(INDENT).append("/** The event's payload. */\n");
    b.append(INDENT).append("public val data: ").append(classOf.apply(e.data())).append(",\n");
    b.append(") : Event {\n");
    b.append(INDENT).append("override val type: String get() = TYPE\n\n");
    b.append(INDENT).append("public companion object {\n");
    b.append(INDENT).append(INDENT).append("/** The wire type. */\n");
    b.append(INDENT).append(INDENT).append("public const val TYPE: String = \"");
    b.append(e.type()).append("\"\n").append(INDENT).append("}\n}\n");
    return b.toString();
  }

  static String inbound(List<Entry> in, UnaryOperator<String> classOf) {
    StringBuilder b =
        new StringBuilder(
            start(
                "import com.getlingara.kotlin.internal.LingaraJson\n"
                    + "import kotlinx.serialization.json.JsonElement\n"
                    + "import kotlinx.serialization.json.JsonObject\n"
                    + "import kotlinx.serialization.json.JsonPrimitive\n\n"));
    b.append("/**\n * An event a client sends with `sendEvent` (ADR 30.9.26aa D3, D8): one class");
    b.append(" per inbound\n * type, named after the model it takes. It is sent as `{type, data}`.");
    b.append("\n */\npublic sealed interface InboundEvent {\n");
    b.append(INDENT).append("/** The wire type, such as `world.context_changed`. */\n");
    b.append(INDENT).append("public val type: String\n");
    for (Entry e : in) {
      b.append('\n').append(INDENT).append("/** A `").append(e.type()).append("` event. */\n");
      b.append(INDENT).append("public data class ").append(e.data()).append("(\n");
      b.append(INDENT).append(INDENT).append("/** The event's data. */\n");
      b.append(INDENT).append(INDENT).append("public val data: ").append(classOf.apply(e.data()));
      b.append(",\n").append(INDENT).append(") : InboundEvent {\n");
      b.append(INDENT).append(INDENT).append("override val type: String get() = \"");
      b.append(e.type()).append("\"\n").append(INDENT).append("}\n");
    }
    b.append("}\n\n/** The `{type, data}` body `sendEvent` posts. */\n");
    b.append("internal fun InboundEvent.toJson(): JsonObject =\n");
    b.append(INDENT).append("when (this) {\n");
    for (Entry e : in) {
      String model = classOf.apply(e.data());
      b.append(INDENT).append(INDENT).append("is InboundEvent.").append(e.data()).append(" ->\n");
      b.append(INDENT).append(INDENT).append(INDENT).append("body(type, ");
      if (model.equals(JSON_ELEMENT)) {
        b.append("data)\n");
      } else {
        b.append("LingaraJson.encodeToJsonElement(").append(model).append(".serializer(), data))\n");
      }
    }
    b.append(INDENT).append("}\n\n");
    b.append("private fun body(\n    type: String,\n    data: JsonElement,\n): JsonObject =");
    b.append(" JsonObject(mapOf(\"type\" to JsonPrimitive(type), \"data\" to data))\n");
    return b.toString();
  }

  static String parser(List<Entry> out, UnaryOperator<String> classOf) {
    StringBuilder b = new StringBuilder(start(PARSER_IMPORTS)).append(PARSER_PREAMBLE);
    for (Entry e : out) {
      String model = classOf.apply(e.data());
      b.append(INDENT).append(INDENT).append(INDENT).append(e.arm()).append(".TYPE -> ");
      b.append(e.arm()).append("(id, createdAt, apiVersion, subject, ");
      if (model.equals(JSON_ELEMENT)) {
        b.append("data)\n");
      } else {
        b.append("LingaraJson.decodeFromJsonElement(").append(model).append(".serializer(), data))\n");
      }
    }
    b.append(INDENT).append(INDENT).append(INDENT);
    b.append("else -> UnknownEvent(id, type, createdAt, apiVersion, subject, data)\n");
    b.append(INDENT).append(INDENT).append("}\n").append(PARSER_HELPERS);
    return b.append("}\n").toString();
  }

  private static final String EVENT =
      start("import kotlinx.serialization.json.JsonElement\n\n")
          + "/**\n"
          + " * One event Lingara sends, by webhook, feed or stream (ADR 30.9.26aa D3): one data"
          + " class per type\n"
          + " * in the catalogue this library was generated from, and [UnknownEvent] for a type"
          + " added since,\n"
          + " * which a receiver still acknowledges. [apiVersion] is the version the data was"
          + " rendered at: pin\n"
          + " * the client to `LingaraClient.GENERATED_FOR_VERSION`.\n"
          + " */\n"
          + "public sealed interface Event {\n"
          + "    /** The event's id, `lgr_evt_…`: the receiver's deduplication key. */\n"
          + "    public val id: String\n\n"
          + "    /** The event's wire type, such as `lesson_plan.ready`. */\n"
          + "    public val type: String\n\n"
          + "    /** When the event happened, an RFC 3339 timestamp. */\n"
          + "    public val createdAt: String\n\n"
          + "    /** The API version the event's data was rendered at. */\n"
          + "    public val apiVersion: String\n\n"
          + "    /** The subject the event is about. */\n"
          + "    public val subject: String\n\n"
          + "    public companion object {\n"
          + "        /**\n"
          + "         * Parses one event envelope. A known type decodes into its class; an unknown"
          + " type is an\n"
          + "         * [UnknownEvent], never an error.\n"
          + "         *\n"
          + "         * @throws IllegalArgumentException for text that is not JSON, not an"
          + " envelope, or a known\n"
          + "         *   type whose data does not decode\n"
          + "         */\n"
          + "        public fun parse(json: String): Event = EventParser.parse(json)\n\n"
          + "        /** Parses one event envelope already read as JSON; throws as [parse] does."
          + " */\n"
          + "        public fun parse(envelope: JsonElement): Event = EventParser.parse(envelope)\n"
          + "    }\n"
          + "}\n";

  private static final String UNKNOWN =
      start("import kotlinx.serialization.json.JsonElement\n\n")
          + "/**\n"
          + " * An event whose type this library does not know: the catalogue is additive, so a"
          + " newer type\n"
          + " * arrives as this, never as an error. Acknowledge it, so the deliverer stops"
          + " retrying, and log it.\n"
          + " */\n"
          + "public data class UnknownEvent(\n"
          + "    override val id: String,\n"
          + "    override val type: String,\n"
          + "    override val createdAt: String,\n"
          + "    override val apiVersion: String,\n"
          + "    override val subject: String,\n"
          + "    /** The event's payload, as JSON. */\n"
          + "    public val data: JsonElement,\n"
          + ") : Event\n";

  private static final String PARSER_IMPORTS =
      "import com.getlingara.kotlin.internal.LingaraJson\n"
          + "import kotlinx.serialization.json.JsonElement\n"
          + "import kotlinx.serialization.json.JsonObject\n"
          + "import kotlinx.serialization.json.JsonPrimitive\n\n";

  private static final String PARSER_PREAMBLE =
      "/**\n"
          + " * [Event.parse]: reads `type` first, then decodes a known type's data into its class"
          + " (ADR\n"
          + " * 30.9.26aa D3). Data is decoded from the JSON tree, which never reads a number into"
          + " a string.\n"
          + " */\n"
          + "internal object EventParser {\n"
          + "    fun parse(json: String): Event = parse(LingaraJson.parseToJsonElement(json))\n\n"
          + "    fun parse(envelope: JsonElement): Event {\n"
          + "        val fields = envelope as? JsonObject ?: throw notAnEnvelope()\n"
          + "        val id = fields.text(\"id\") ?: throw notAnEnvelope()\n"
          + "        val type = fields.text(\"type\") ?: throw notAnEnvelope()\n"
          + "        val createdAt = fields.text(\"created_at\") ?: throw notAnEnvelope()\n"
          + "        val apiVersion = fields.text(\"api_version\") ?: throw notAnEnvelope()\n"
          + "        val subject = fields.text(\"subject\") ?: throw notAnEnvelope()\n"
          + "        val data = fields[\"data\"] as? JsonObject ?: throw notAnEnvelope()\n"
          + "        return when (type) {\n";

  private static final String PARSER_HELPERS =
      "    }\n\n"
          + "    private fun notAnEnvelope(): IllegalArgumentException ="
          + " IllegalArgumentException(\"the value is not an event envelope\")\n\n"
          + "    private fun JsonObject.text(name: String): String? ="
          + " (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content\n";
}
