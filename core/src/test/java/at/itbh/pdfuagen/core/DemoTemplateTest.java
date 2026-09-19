/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Renders the demo template shipped in {@code demo/} in every format. */
class DemoTemplateTest {

  private static final Path DEMO = Path.of("..", "demo");

  private final DocumentRenderer renderer = new DocumentRenderer();

  private RenderRequest request() throws Exception {
    Map<String, Object> data;
    try (InputStream in = Files.newInputStream(DEMO.resolve("data.json"))) {
      data = JsonData.parse(in);
    }
    return new RenderRequest(
        "demo.xhtml",
        new DirectoryTemplateRepository(DEMO, List.of()),
        data,
        Map.of("photo", Files.readAllBytes(DEMO.resolve("photo.png"))));
  }

  @Test
  void pdfConformsToPdfUa1() throws Exception {
    Rendered pdf = renderer.render(request(), OutputFormat.PDF);

    PdfUaValidator.Report report = PdfUaValidator.validate(pdf.content());
    assertTrue(report.compliant(), () -> String.join("\n", report.failures()));
  }

  @Test
  void xhtmlIsSelfContained() throws Exception {
    String xhtml =
        new String(
            renderer.render(request(), OutputFormat.XHTML).content(), StandardCharsets.UTF_8);

    assertTrue(xhtml.contains("src=\"data:image/svg+xml;base64,"), "logo embedded");
    assertTrue(xhtml.contains("url(\"data:font/ttf;base64,"), "fonts embedded");
    assertTrue(xhtml.contains("<li>Second bullet item</li>"), "data rendered");
    assertFalse(xhtml.contains("<bookmarks"), "renderer-specific elements removed");
  }
}
