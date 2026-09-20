/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.server;

import at.itbh.pdfuagen.server.store.Bundle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Revision bundles of the demo template and its layout, for the tests. */
final class DemoBundles {

  static final Path DEMO = Path.of("..", "demo");

  private DemoBundles() {}

  static byte[] demoBundle(int layoutRevision) throws Exception {
    return demoBundle("demo-layout@" + layoutRevision);
  }

  /** The demo content, pinning the given layout revision. */
  static byte[] demoBundle(String layout) throws Exception {
    Map<String, byte[]> files = new TreeMap<>();
    files.put("template.xhtml", Files.readAllBytes(DEMO.resolve("demo.xhtml")));
    files.put(
        "template.json",
        Files.readString(DEMO.resolve("demo.json"))
            .replace("demo-layout@1", layout)
            .getBytes(StandardCharsets.UTF_8));
    files.put("example.json", Files.readAllBytes(DEMO.resolve("data.json")));
    files.put("example/photo.png", Files.readAllBytes(DEMO.resolve("photo.png")));
    return Bundle.write(files);
  }

  /** The demo layout; {@code header} replaces its page header text. */
  static byte[] layoutBundle(String header) throws Exception {
    return layoutBundle("layout", header);
  }

  static byte[] layoutBundle(String directory, String header) throws Exception {
    Path layout = DEMO.resolve(directory);
    Map<String, byte[]> files = new TreeMap<>();
    try (var paths = Files.walk(layout)) {
      for (Path file : paths.filter(Files::isRegularFile).toList()) {
        files.put(layout.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
      }
    }
    files.put(
        "messages.json",
        Files.readString(layout.resolve("messages.json"))
            .replace("Accessible document example", header)
            .getBytes(StandardCharsets.UTF_8));
    files.put("example.json", "{}".getBytes(StandardCharsets.UTF_8));
    return Bundle.write(files);
  }

  /** A data file of the demo, e.g. {@code data-email.json}. */
  static String data(String file) throws Exception {
    return Files.readString(DEMO.resolve(file), StandardCharsets.UTF_8);
  }
}
