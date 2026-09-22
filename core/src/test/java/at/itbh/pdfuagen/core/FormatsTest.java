/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/** The demo template in the formats written from the document model. */
class FormatsTest {

  private static final Path DEMO = Path.of("..", "demo");

  private final DocumentRenderer renderer =
      new DocumentRenderer(
          ResourceFetcher.NONE,
          ResourceLimits.DEFAULT,
          Duration.ofSeconds(30),
          URI.create("https://assets.example.invalid/"));

  private RenderRequest request(String data, boolean photo) throws Exception {
    Map<String, Object> values;
    try (InputStream in = Files.newInputStream(DEMO.resolve(data))) {
      values = JsonData.parse(in);
    }
    Map<String, byte[]> attachments =
        photo ? Map.of("photo", Files.readAllBytes(DEMO.resolve("photo.png"))) : Map.of();
    return new RenderRequest("demo.xhtml", Demo.repository(), values, attachments);
  }

  private static Map<String, String> unzip(byte[] zip) throws Exception {
    Map<String, String> parts = new HashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        parts.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return parts;
  }

  @Test
  void docxCarriesTheAccessibilityStructure() throws Exception {
    Map<String, String> parts =
        unzip(renderer.render(request("data.json", true), OutputFormat.DOCX).content());
    String document = parts.get("word/document.xml");

    assertTrue(document.contains("<w:pStyle w:val=\"Heading1\""));
    assertTrue(document.contains("descr=\"Logo of IT Beratung Hermann GmbH\""));
    assertTrue(document.contains("adec:decorative"), "decorative image flagged");
    assertTrue(document.contains("asvg:svgBlip"), "SVG embedded as vector with a PNG fallback");
    assertTrue(parts.get("[Content_Types].xml").contains("image/svg+xml"));
    assertTrue(document.contains("<w:tblHeader"));
    assertTrue(document.contains("<w:tblCaption w:val=\"Order 2026-0042 of September 19, 2026\""));
    assertTrue(document.contains("<w:footnoteReference"));
    assertTrue(document.contains("<w:lang w:val=\"de-AT\""));
    assertTrue(parts.get("word/footer1.xml").contains("NUMPAGES"));
    assertTrue(
        parts
            .get("docProps/core.xml")
            .contains("<dc:title>All-in-one accessible document example"));
  }

  @Test
  void odtCarriesTheAccessibilityStructure() throws Exception {
    Map<String, String> parts =
        unzip(renderer.render(request("data.json", true), OutputFormat.ODT).content());
    String content = parts.get("content.xml");

    assertEquals("application/vnd.oasis.opendocument.text", parts.get("mimetype"));
    assertTrue(content.contains("text:outline-level=\"1\""));
    assertTrue(content.contains("<svg:title>Logo of IT Beratung Hermann GmbH</svg:title>"));
    assertTrue(content.contains("draw:mime-type=\"image/svg+xml\""), "SVG kept as vector");
    assertTrue(parts.get("META-INF/manifest.xml").contains("image/svg+xml"));
    assertTrue(content.contains("<table:table-header-rows>"));
    assertTrue(content.contains("text:note-class=\"footnote\""));
    assertTrue(content.contains("fo:language=\"de\""));
    assertTrue(parts.get("styles.xml").contains("<text:page-count>"));
    assertTrue(parts.get("meta.xml").contains("<dc:language>en</dc:language>"));
  }

  @Test
  void emailHtmlUsesPublicAssetUrlsAndInlineStyles() throws Exception {
    String html =
        new String(
            renderer.render(request("data-email.json", false), OutputFormat.EMAIL_HTML).content(),
            StandardCharsets.UTF_8);
    String logoHash = DocumentRenderer.sha256(Files.readAllBytes(DEMO.resolve("layout/logo.svg")));

    assertTrue(html.contains("src=\"https://assets.example.invalid/assets/" + logoHash + ".png\""));
    assertTrue(html.contains("role=\"article\""));
    assertTrue(html.contains("<h1 id=\"top\" style=\""));
    assertTrue(html.contains("alt=\"\" role=\"presentation\""));
    assertFalse(html.contains("<style"));
  }

  @Test
  void emailHtmlRejectsAttachments() throws Exception {
    RenderException e =
        assertThrows(
            RenderException.class,
            () -> renderer.render(request("data.json", true), OutputFormat.EMAIL_HTML));
    assertEquals(Problem.RESOURCE_REJECTED, e.problems().getFirst().type());
  }

  @Test
  void plainTextListsFootnotesAndLinks() throws Exception {
    String text =
        new String(
            renderer.render(request("data.json", true), OutputFormat.TEXT).content(),
            StandardCharsets.UTF_8);

    // The layout's logo comes first, as its alt text.
    assertTrue(text.startsWith("[Logo of IT Beratung Hermann GmbH]\n\nAll-in-one accessible"));
    assertTrue(text.contains("All-in-one accessible document example\n=========="));
    assertTrue(text.contains("homepage <https://www.itbh.at/>"));
    assertTrue(text.contains("[1] Footnotes are real footnotes"));
    assertTrue(text.lines().allMatch(line -> line.length() <= 72), text);
  }

  @Test
  void inaccessibleContentFailsTheModelFormats() {
    RenderRequest request =
        new RenderRequest(
            "t",
            new MapTemplateRepository()
                .template(
                    "t",
                    "<html"
                        + " lang=\"en\"><head><title>T</title></head><body><h3>x</h3></body></html>"),
            Map.of(),
            Map.of());
    RenderException e =
        assertThrows(RenderException.class, () -> renderer.render(request, OutputFormat.DOCX));
    assertEquals(Problem.ACCESSIBILITY, e.problems().getFirst().type());
  }
}
