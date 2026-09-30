package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class GeneratedImportsTest {
  private static final Pattern FORBIDDEN =
      Pattern.compile("javax\\.|jakarta\\.|org\\.openapitools|\\bApiClient\\b|\\bJSON\\.");

  /** A package-qualified name: lower-case segments, then a type. */
  private static final Pattern QUALIFIED = Pattern.compile("\\b((?:[a-z][a-z0-9_]*\\.)+)[A-Z]\\w*");

  private static final List<String> ALLOWED =
      List.of("java.", "com.fasterxml.jackson.", "com.getlingara.client.");

  /**
   * 29.9.26r AC23: no line of a generated source, fully qualified annotations included, names a
   * javax., jakarta. or org.openapitools package or the generator's ApiClient or JSON classes, and
   * every package a generated line names is under java.*, com.fasterxml.jackson.* or
   * com.getlingara.client.*.
   */
  @Test
  void generatedSourcesReferenceOnlyAllowedPackages() throws IOException {
    List<String> offences = new ArrayList<>();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(Path.of(System.getProperty("lingara.generated")))) {
      files = walk.filter(p -> p.toString().endsWith(".java")).toList();
    }
    assertTrue(files.size() > 30, "the generated tree was found: " + files.size());
    for (Path file : files) {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++) {
        String where = file.getFileName() + ":" + (i + 1) + ": ";
        if (FORBIDDEN.matcher(lines.get(i)).find()) {
          offences.add(where + lines.get(i));
        }
        Matcher m = QUALIFIED.matcher(lines.get(i));
        while (m.find()) {
          String pkg = m.group(1);
          if (ALLOWED.stream().noneMatch(pkg::startsWith)) {
            offences.add(where + m.group());
          }
        }
      }
    }
    assertEquals(List.of(), offences);
  }
}
