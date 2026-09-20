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
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Renders the demo template shipped in {@code demo/} in every format. */
class DemoTemplateTest {

  private static final Path DEMO = Path.of("..", "demo");

  private final DocumentRenderer renderer = new DocumentRenderer();

  private RenderRequest request() throws Exception {
    return request("demo.xhtml");
  }

  private RenderRequest request(String template) throws Exception {
    Map<String, Object> data;
    try (InputStream in = Files.newInputStream(DEMO.resolve("data.json"))) {
      data = JsonData.parse(in);
    }
    return new RenderRequest(
        template,
        Demo.repository(),
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

  @Test
  void germanVariantRendersInGermanAndConformsToPdfUa1() throws Exception {
    RenderRequest de = request("demo.de.xhtml");

    String xhtml =
        new String(renderer.render(de, OutputFormat.XHTML).content(), StandardCharsets.UTF_8);
    assertTrue(xhtml.contains("lang=\"de\""), "document language is German");
    // Layout message and date formatting follow the variant's language.
    assertTrue(xhtml.contains("Logo der IT Beratung Hermann GmbH"), "German layout text");
    assertTrue(xhtml.contains("vom 19. September 2026"), "German date formatting");

    PdfUaValidator.Report report =
        PdfUaValidator.validate(renderer.render(de, OutputFormat.PDF).content());
    assertTrue(report.compliant(), () -> String.join("\n", report.failures()));
  }
}
