/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateCheckTest {

  private static final String DESCRIPTOR =
      "{\"language\": \"en\", \"formats\": [\"pdf\", \"text\"],"
          + " \"fields\": {\"name\": {\"type\": \"text\"}}}";

  private final DocumentRenderer renderer = new DocumentRenderer();

  private static String page(String lang, String body) {
    return "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\""
        + lang
        + "\"><head><title>T</title></head><body><p>"
        + body
        + "</p></body></html>";
  }

  @Test
  void demoPasses() throws Exception {
    Map<String, Object> data;
    try (InputStream in = Files.newInputStream(Demo.CONTENT.resolve("example.json"))) {
      data = JsonData.parse(in);
    }
    TemplateCheck.Report report =
        TemplateCheck.check(
            renderer,
            Demo.repository(),
            "template.xhtml",
            data,
            Map.of("photo", Files.readAllBytes(Demo.CONTENT.resolve("example/photo.png"))));
    assertTrue(report.passed(), report.problems()::toString);
    assertEquals(
        List.of(
            "email-html not checked: the example data uses attachments, which email HTML cannot"
                + " show"),
        report.warnings());
  }

  @Test
  void reportsInvalidExampleDataAndWrongLanguage() {
    MapTemplateRepository repository =
        new MapTemplateRepository()
            .template("t.xhtml", page("en", "{name}"))
            .template("t.de.xhtml", page("en", "{name}"))
            .resource("t.json", DESCRIPTOR);
    repository.language("t.xhtml", "de");

    TemplateCheck.Report report =
        TemplateCheck.check(renderer, repository, "t.xhtml", Map.of("name", 1), Map.of());

    assertFalse(report.passed());
    assertEquals(
        List.of(new Problem(Problem.INVALID_DATA, "must be text (a JSON string)", "#/name")),
        report.problems());

    report = TemplateCheck.check(renderer, repository, "t.xhtml", Map.of("name", "Jane"), Map.of());
    assertEquals(
        List.of(
            new Problem(
                Problem.ACCESSIBILITY,
                "the html element declares lang=\"en\", but the variant is written in de",
                "t.de.xhtml")),
        report.problems());
  }

  @Test
  void refusesFormatsTheTemplateDoesNotOffer() {
    MapTemplateRepository repository =
        new MapTemplateRepository()
            .template("t.xhtml", page("en", "{name}"))
            .resource("t.json", DESCRIPTOR);
    RenderException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            RenderException.class,
            () ->
                renderer.render(
                    new RenderRequest("t.xhtml", repository, Map.of("name", "x"), Map.of()),
                    OutputFormat.DOCX));
    assertEquals(
        List.of(
            new Problem(
                Problem.FORMAT_NOT_SUPPORTED,
                "the template does not offer docx; it offers pdf, text",
                "t.xhtml")),
        e.problems());
  }

  @Test
  void requiresADescriptor() {
    MapTemplateRepository repository =
        new MapTemplateRepository().template("t.xhtml", page("en", "x"));
    TemplateCheck.Report report =
        TemplateCheck.check(renderer, repository, "t.xhtml", Map.of(), Map.of());
    assertEquals(
        "the template has no field definitions; add t.json", report.problems().getFirst().detail());
  }

  @Test
  void renderValidatesDataWhenTheTemplateHasADescriptor() {
    MapTemplateRepository repository =
        new MapTemplateRepository()
            .template("t.xhtml", page("en", "{name}"))
            .resource("t.json", DESCRIPTOR);
    RenderException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            RenderException.class,
            () ->
                renderer.render(
                    new RenderRequest("t.xhtml", repository, Map.of("x", BigDecimal.ONE), Map.of()),
                    OutputFormat.PDF));
    assertEquals(List.of(new Problem(Problem.INVALID_DATA, "is required", "#/name")), e.problems());
  }
}
