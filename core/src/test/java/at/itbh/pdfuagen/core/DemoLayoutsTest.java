/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** One document, one set of data, two layouts: the content does not change, the look does. */
class DemoLayoutsTest {

  private final DocumentRenderer renderer =
      new DocumentRenderer(
          ResourceFetcher.NONE,
          ResourceLimits.DEFAULT,
          Duration.ofSeconds(30),
          URI.create("https://assets.example.invalid/"));

  private static Map<String, Object> data() throws Exception {
    try (InputStream in = Files.newInputStream(Demo.DIR.resolve("data.json"))) {
      return JsonData.parse(in);
    }
  }

  private static Map<String, byte[]> photo() throws Exception {
    return Map.of("photo", Files.readAllBytes(Demo.DIR.resolve("photo.png")));
  }

  @Test
  void theSameDocumentPassesEveryCheckInEveryLayout() throws Exception {
    for (String layout : Demo.LAYOUTS) {
      TemplateCheck.Report report =
          TemplateCheck.check(renderer, Demo.repository(layout), "demo.xhtml", data(), photo());
      assertTrue(report.passed(), layout + ": " + report.problems());
    }
  }

  @Test
  void theLayoutDecidesTheLookNotTheContent() throws Exception {
    Map<String, String> xhtml = new HashMap<>();
    Map<String, String> text = new HashMap<>();
    for (String layout : Demo.LAYOUTS) {
      RenderRequest request =
          new RenderRequest("demo.xhtml", Demo.repository(layout), data(), photo());
      xhtml.put(layout, utf8(renderer.render(request, OutputFormat.XHTML).content()));
      text.put(layout, utf8(renderer.render(request, OutputFormat.TEXT).content()));
    }
    // Same content and data …
    for (String html : xhtml.values()) {
      assertTrue(html.contains("Hello Jane Doe."), html);
      assertTrue(html.contains("€1,450.00"), html);
    }
    // … in another look: stylesheet, page header, texts and component markup of the layout.
    assertTrue(xhtml.get("layout-memo").contains("MEMORANDUM"));
    assertFalse(xhtml.get("layout").contains("MEMORANDUM"));
    assertTrue(xhtml.get("layout").contains("Accessible document example"));
    assertTrue(xhtml.get("layout-memo").contains("Internal memo"));
    assertTrue(xhtml.get("layout-memo").contains("<strong>Note:</strong>"));
    assertTrue(xhtml.get("layout").contains("<strong>Note</strong>"));
    // Plain text carries the content and the layout's texts, not its look.
    assertEquals(
        text.get("layout").lines().filter(l -> l.startsWith("Hello")).toList(),
        text.get("layout-memo").lines().filter(l -> l.startsWith("Hello")).toList());
    assertTrue(text.get("layout-memo").contains("MEMORANDUM"));
  }

  private static String utf8(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
