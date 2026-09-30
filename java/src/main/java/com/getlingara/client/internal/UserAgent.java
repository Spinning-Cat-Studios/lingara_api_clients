package com.getlingara.client.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * K6: {@code lingara-java/<version> (jvm/<Runtime.version()>)}, then a caller's own product token
 * after one space. The library's token always comes first (CONTRACT.md K6).
 */
public final class UserAgent {
  /** Visible ASCII with no {@code )}: what K6 allows inside the parentheses. */
  private static final Pattern RUNTIME = Pattern.compile("[\\x20-\\x28\\x2A-\\x7E]+");

  private static final String VERSION = loadVersion();

  private UserAgent() {}

  /**
   * Returns the header value.
   *
   * @param suffix a caller's product token, or null
   * @return the {@code User-Agent}
   */
  public static String of(String suffix) {
    String own = "lingara-java/" + VERSION + " (jvm/" + runtime(Runtime.version().toString()) + ")";
    return suffix == null || suffix.isEmpty() ? own : own + " " + suffix;
  }

  /**
   * Returns this library's released version, from the generated {@code version.properties}.
   *
   * @return the version
   */
  public static String version() {
    return VERSION;
  }

  static String runtime(String version) {
    return RUNTIME.matcher(version).matches() ? version : "unknown";
  }

  private static String loadVersion() {
    try (InputStream in =
        UserAgent.class.getResourceAsStream("/com/getlingara/client/version.properties")) {
      if (in == null) {
        throw new IllegalStateException("version.properties is missing: run make codegen-java");
      }
      Properties properties = new Properties();
      properties.load(in);
      return properties.getProperty("version");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
