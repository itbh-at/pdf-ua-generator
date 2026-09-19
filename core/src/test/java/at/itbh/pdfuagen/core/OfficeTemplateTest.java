/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/** DOCX and ODT take styles, page setup and fonts from the layout's .dotx and .ott. */
class OfficeTemplateTest {

  private final DocumentRenderer renderer = new DocumentRenderer();

  private byte[] render(String layout, OutputFormat format) throws Exception {
    Map<String, Object> data;
    try (InputStream in = Files.newInputStream(Demo.DIR.resolve("data.json"))) {
      data = JsonData.parse(in);
    }
    return renderer
        .render(
            new RenderRequest(
                "demo.xhtml",
                Demo.repository(layout),
                data,
                Map.of("photo", Files.readAllBytes(Demo.DIR.resolve("photo.png")))),
            format)
        .content();
  }

  private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
    Map<String, byte[]> parts = new HashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        parts.put(entry.getName(), in.readAllBytes());
      }
    }
    return parts;
  }

  private static String text(Map<String, byte[]> parts, String name) {
    return new String(parts.get(name), StandardCharsets.UTF_8);
  }

  private static byte[] font() throws Exception {
    return Files.readAllBytes(Demo.DIR.resolve("layout-memo/fonts/SpecialElite-Regular.ttf"));
  }

  @Test
  void docxUsesTheWordTemplateAndEmbedsTheLayoutsFonts() throws Exception {
    Map<String, byte[]> parts = unzip(render("layout-memo", OutputFormat.DOCX));
    String styles = text(parts, "word/styles.xml");
    String document = text(parts, "word/document.xml");

    // Styles and default font of the template; catalog styles found by name.
    assertTrue(styles.contains("w:ascii=\"Special Elite\""), styles);
    assertTrue(document.contains("<w:pStyle w:val=\"lead\""), document);
    assertFalse(styles.contains("Catalog-lead"));
    // Styles the template lacks are added.
    assertTrue(styles.contains("w:styleId=\"TableGrid\""));
    // The document language wins over the template's.
    assertTrue(styles.contains("<w:lang w:bidi=\"en\" w:eastAsia=\"en\" w:val=\"en\"/>"), styles);
    // Page setup of the template: 30 mm left margin.
    assertTrue(document.contains("w:left=\"1701\""), document);

    // The font, embedded and obfuscated; de-obfuscating gives the layout's file back.
    String table = text(parts, "word/fontTable.xml");
    assertTrue(table.contains("w:name=\"Special Elite\""), table);
    String key = table.replaceAll("(?s).*w:fontKey=\"([^\"]+)\".*", "$1");
    assertArrayEquals(
        font(),
        at.itbh.pdfuagen.core.writer.OfficeTemplateAccess.obfuscate(
            parts.get("word/fonts/font1.odttf"), key));
    assertTrue(text(parts, "word/settings.xml").contains("<w:embedTrueTypeFonts/>"));
    assertTrue(text(parts, "[Content_Types].xml").contains("obfuscatedFont"));
  }

  @Test
  void odtUsesTheOdfTemplateAndEmbedsTheLayoutsFonts() throws Exception {
    Map<String, byte[]> parts = unzip(render("layout-memo", OutputFormat.ODT));
    String styles = text(parts, "styles.xml");

    assertTrue(styles.contains("fo:margin-left=\"3cm\""), styles);
    assertTrue(styles.contains("xlink:href=\"Fonts/Special_Elite.ttf\""), styles);
    assertTrue(text(parts, "content.xml").contains("text:style-name=\"lead\""));
    assertFalse(styles.contains("Catalog_lead"));
    assertArrayEquals(font(), parts.get("Fonts/Special_Elite.ttf"));
    assertTrue(
        text(parts, "META-INF/manifest.xml")
            .contains("manifest:full-path=\"Fonts/Special_Elite.ttf\""));
  }

  @Test
  void theTwoLayoutsGiveDifferentOfficeDocuments() throws Exception {
    String blue = text(unzip(render("layout", OutputFormat.DOCX)), "word/styles.xml");
    String memo = text(unzip(render("layout-memo", OutputFormat.DOCX)), "word/styles.xml");
    assertTrue(blue.contains("w:ascii=\"Roboto\"") && blue.contains("0069B4"));
    assertTrue(memo.contains("8B1E1E") && !memo.contains("Roboto"));
  }

  @Test
  void withoutALayoutTheWritersKeepTheirOwnStyles() throws Exception {
    MapTemplateRepository repository =
        new MapTemplateRepository()
            .template(
                "t.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en\"><head><title>T</title>"
                    + "</head><body><h1>T</h1><p>x</p></body></html>");
    Map<String, byte[]> parts =
        unzip(
            renderer
                .render(
                    new RenderRequest("t.xhtml", repository, Map.of(), Map.of()), OutputFormat.DOCX)
                .content());
    assertTrue(text(parts, "word/styles.xml").contains("w:ascii=\"Arial\""));
    assertFalse(parts.containsKey("word/fontTable.xml"));
  }
}
