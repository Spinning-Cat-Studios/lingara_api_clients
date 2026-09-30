package com.getlingara.codegen;

import com.getlingara.codegen.StreamsCodegen.Ending;
import com.getlingara.codegen.StreamsCodegen.Event;
import com.getlingara.codegen.StreamsCodegen.Stream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * StreamsCodegen's Kotlin emitter (ADR 29.9.26s D2): each stream's union as a sealed interface of
 * {@code Serializable} data classes, {@code internal.Streams} as an internal object, and {@code
 * internal.BuildInfo}. It renders the same {@link Stream}s the Java emitter does.
 *
 * <p>Every payload type is written fully qualified, so a member named after its event ({@code
 * Pending}) can never shadow a payload type of the same simple name.
 */
final class KotlinSource {
  static final String BASE = "com.getlingara.kotlin";
  static final String MODEL = BASE + ".model";
  private static final String INDENT = "    ";

  private KotlinSource() {}

  /** One union: a sealed interface with a data class per event the stream yields. */
  static String union(Stream s, UnaryOperator<String> classOf) {
    StringBuilder b = new StringBuilder(StreamsCodegen.HEADER);
    b.append("\npackage ").append(MODEL).append("\n\n");
    b.append("import kotlinx.serialization.Serializable\n\n");
    b.append("/**\n * One event of `").append(s.operationId()).append("`'s stream.\n *\n");
    b.append(" * The error event is thrown and a `Done`-bodied ending event ends the stream\n");
    b.append(" * unemitted, so neither has a member: a `when` covers exactly what the stream\n");
    b.append(" * can emit.\n */\n");
    b.append("public sealed interface ").append(s.union()).append(" {\n");
    boolean first = true;
    for (Event e : s.events()) {
      if (e.yielded()) {
        b.append(first ? "" : "\n");
        first = false;
        b.append(INDENT).append("/** The `").append(e.name()).append("` event. */\n");
        b.append(INDENT).append("@Serializable\n");
        b.append(INDENT).append("public data class ").append(StreamsCodegen.pascal(e.name()));
        b.append("(public val data: ").append(classOf.apply(e.data())).append(") : ");
        b.append(s.union()).append('\n');
      }
    }
    return b.append("}\n").toString();
  }

  /** The version constants: the library's own, and the view's {@code info.version}. */
  static String buildInfo(String version, String generatedFor) {
    return StreamsCodegen.HEADER
        + "\npackage "
        + BASE
        + ".internal\n\n"
        + "/** This library's version and the API version its models were generated from. */\n"
        + "internal object BuildInfo {\n"
        + "    /** The repository's VERSION, sent in every `User-Agent` (K6). */\n"
        + "    const val VERSION: String = \""
        + version
        + "\"\n\n"
        + "    /** The view's `info.version` (ADR 30.9.26a). */\n"
        + "    const val GENERATED_FOR_VERSION: String = \""
        + generatedFor
        + "\"\n"
        + "}\n";
  }

  /** {@code internal.Streams}: a route per stream, the routes by operationId, a decoder each. */
  static String streams(List<Stream> streams, UnaryOperator<String> classOf) {
    StringBuilder b = new StringBuilder(StreamsCodegen.HEADER);
    b.append("\npackage ").append(BASE).append(".internal\n\n");
    b.append("import kotlinx.serialization.KSerializer\n");
    b.append("import kotlinx.serialization.json.Json\n");
    b.append("import kotlinx.serialization.json.JsonElement\n");
    b.append("import kotlin.reflect.KClass\n\n");
    b.append(PREAMBLE);
    for (Stream s : streams) {
      route(b, s, classOf);
    }
    routes(b, streams);
    for (Stream s : streams) {
      decoder(b, s, classOf);
    }
    return b.append("}\n").toString();
  }

  private static void route(StringBuilder b, Stream s, UnaryOperator<String> classOf) {
    String union = MODEL + "." + s.union();
    b.append("\n    /** `").append(s.operationId()).append("`. */\n");
    b.append("    val ").append(StreamsCodegen.StreamsSource.constant(s.operationId()));
    b.append(": Route<").append(union).append("> =\n        Route(\n");
    line(b, quote(s.operationId()));
    line(b, quote(s.method()));
    line(b, quote(s.path()));
    line(b, s.requestBody() == null ? "null" : MODEL + "." + s.requestBody() + "::class");
    line(b, "listOf(" + quoted(s.pathParameters()) + ")");
    List<String> names = new ArrayList<>();
    List<String> ends = new ArrayList<>();
    List<String> serializers = new ArrayList<>();
    for (Event e : s.events()) {
      names.add(quote(e.name()));
      serializers.add(quote(e.name()) + " to " + classOf.apply(e.data()) + ".serializer()");
      if (e.ending() != Ending.NONE) {
        ends.add(quote(e.name()) + " to Ending." + e.ending());
      }
    }
    line(b, "listOf(" + String.join(", ", names) + ")");
    line(b, "mapOf(" + String.join(", ", ends) + ")");
    line(b, "mapOf(" + String.join(", ", serializers) + ")");
    line(b, "::decode" + StreamsCodegen.pascal(s.operationId()));
    b.append("        )\n");
  }

  private static void routes(StringBuilder b, List<Stream> streams) {
    List<String> pairs = new ArrayList<>();
    for (Stream s : streams) {
      pairs.add(
          quote(s.operationId()) + " to " + StreamsCodegen.StreamsSource.constant(s.operationId()));
    }
    b.append("\n    /** Every stream route, by operationId. */\n");
    b.append("    val ROUTES: Map<String, Route<*>> =\n        mapOf(");
    b.append(String.join(", ", pairs)).append(")\n");
  }

  private static void decoder(StringBuilder b, Stream s, UnaryOperator<String> classOf) {
    String union = MODEL + "." + s.union();
    b.append("\n    /** Decodes one `").append(s.operationId());
    b.append("` event, or returns null for a name the union has no member for. */\n");
    b.append("    private fun decode").append(StreamsCodegen.pascal(s.operationId()));
    b.append("(\n        event: String,\n        data: JsonElement,\n        json: Json,\n");
    b.append("    ): ").append(union).append("? =\n        when (event) {\n");
    for (Event e : s.events()) {
      if (e.yielded()) {
        String type = classOf.apply(e.data());
        b.append("            ").append(quote(e.name())).append(" -> ");
        b.append(union).append('.').append(StreamsCodegen.pascal(e.name()));
        b.append("(json.decodeFromJsonElement(").append(type).append(".serializer(), data))\n");
      }
    }
    b.append("            else -> null\n        }\n");
  }

  private static void line(StringBuilder b, String argument) {
    b.append("            ").append(argument).append(",\n");
  }

  private static String quote(String value) {
    return "\"" + value.replace("$", "\\$") + "\"";
  }

  private static String quoted(List<String> values) {
    List<String> out = new ArrayList<>();
    values.forEach(v -> out.add(quote(v)));
    return String.join(", ", out);
  }

  static final String PREAMBLE =
      "/**\n"
          + " * Each stream's route, event names, terminal table and payload serializers, from the\n"
          + " * view's `x-lingara-streams`, and a decoder per stream (ADR 29.9.26s D2, ADR"
          + " 29.9.26ai).\n"
          + " */\n"
          + "internal object Streams {\n"
          + "    /** What an ending event does to a stream (CONTRACT.md K5). */\n"
          + "    enum class Ending {\n"
          + "        /** Emitted, then the stream ends. */\n"
          + "        YIELD,\n\n"
          + "        /** A `Done` payload: the stream ends unemitted. */\n"
          + "        QUIET,\n\n"
          + "        /** The failure event: thrown as an `ApiException` with status 200. */\n"
          + "        RAISE,\n"
          + "    }\n\n"
          + "    /** One stream operation; `decode` returns null for an event with no member. */\n"
          + "    class Route<E : Any>(\n"
          + "        val operationId: String,\n"
          + "        val method: String,\n"
          + "        val path: String,\n"
          + "        val requestBody: KClass<*>?,\n"
          + "        val pathParameters: List<String>,\n"
          + "        val events: List<String>,\n"
          + "        val endsOn: Map<String, Ending>,\n"
          + "        val dataSerializers: Map<String, KSerializer<*>>,\n"
          + "        val decode: (String, JsonElement, Json) -> E?,\n"
          + "    )\n";
}
