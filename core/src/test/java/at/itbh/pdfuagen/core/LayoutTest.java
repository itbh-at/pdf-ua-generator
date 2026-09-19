/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** Layouts, their components and texts, and the rules for content templates that fill them. */
class LayoutTest {

  private static final String LAYOUT_JSON =
      """
      {
        "language": "en",
        "styles": {
          "lead": { "label": "Lead" },
          "mark": { "kind": "character" }
        },
        "classes": ["box"],
        "areas": { "title": { "required": true }, "body": { "required": true } },
        "components": { "box": { "parameters": { "title": { "required": true } } } },
        "fonts": ["Roboto"],
        "freeStyling": true,
        "fields": { "company": { "type": "text" } }
      }
      """;

  private static final String SKELETON =
      """
      <html xmlns="http://www.w3.org/1999/xhtml" lang="{doc:lang}"><head>
      <title>{#insert title}Preview{/insert}</title>
      <link rel="stylesheet" href="layout/layout.css" />
      </head><body><p>{company} – {msg:greeting}</p><h1>{#insert title}Preview{/insert}</h1>
      {#insert body}<p>Body</p>{/insert}</body></html>
      """;

  private final DocumentRenderer renderer = new DocumentRenderer();

  private static MapTemplateRepository layout() {
    return new MapTemplateRepository()
        .resource("layout.json", LAYOUT_JSON)
        .template("template.xhtml", SKELETON)
        .template("components/box.xhtml", "<div class=\"box\"><p>{title}</p>{nested-content}</div>")
        .resource("layout.css", ".lead { font-size: 12pt } .mark { color: red }")
        .resource("messages.json", "{\"greeting\": \"Hello\"}")
        .resource("messages.de.json", "{\"greeting\": \"Hallo\"}");
  }

  private static TemplateRepository content(String body, String descriptorExtra) {
    MapTemplateRepository content =
        new MapTemplateRepository()
            .template(
                "t.xhtml",
                "{#include layout}{#title}Invoice{/title}{#body}" + body + "{/body}{/include}")
            .resource(
                "t.json",
                "{\"language\": \"en\", \"layout\": \"corporate@1\""
                    + descriptorExtra
                    + ", \"fields\": {\"name\": {\"type\": \"text\"}}}");
    return new ComposedTemplateRepository(content, layout());
  }

  private String render(TemplateRepository repository, String id) throws RenderException {
    return renderer.renderSource(
        new RenderRequest(id, repository, Map.of("company", "ACME", "name", "Jane"), Map.of()));
  }

  private List<String> problems(TemplateRepository repository) {
    RenderException e =
        assertThrows(RenderException.class, () -> renderer.schema(repository, "t.xhtml"));
    return e.problems().stream().map(Problem::detail).toList();
  }

  @Test
  void contentFillsTheLayoutWithComponentsAndTexts() throws Exception {
    String html =
        render(
            content("<p class=\"lead\">{name}</p>{#box title='Note'}<p>in</p>{/box}", ""),
            "t.xhtml");
    assertTrue(html.contains("<title>Invoice</title>"), html);
    assertTrue(html.contains("<p>ACME – Hello</p>"), html);
    assertTrue(html.contains("<div class=\"box\"><p>Note</p><p>in</p></div>"), html);
    assertTrue(html.contains("lang=\"en\""), html);
  }

  @Test
  void textsFollowTheLanguageOfTheVariant() throws Exception {
    MapTemplateRepository files =
        new MapTemplateRepository()
            .template(
                "t.xhtml", "{#include layout}{#title}T{/title}{#body}<p>x</p>{/body}{/include}")
            .template(
                "t.de-AT.xhtml",
                "{#include layout}{#title}T{/title}{#body}<p>x</p>{/body}{/include}")
            .language("t.xhtml", "de-AT")
            .resource(
                "t.json", "{\"language\": \"en\", \"layout\": \"corporate@1\", \"fields\": {}}");
    TemplateRepository repository = new ComposedTemplateRepository(files, layout());
    String german = render(repository, "t.de-AT.xhtml");
    // de-AT falls back to messages.de.json.
    assertTrue(german.contains("ACME – Hallo"), german);
    assertTrue(german.contains("lang=\"de-AT\""), german);
    assertTrue(render(repository, "t.xhtml").contains("ACME – Hello"));
  }

  @Test
  void schemaIncludesTheLayoutsFields() throws Exception {
    String schema = renderer.schema(content("<p>{name}</p>", ""), "t.xhtml").toJson();
    assertTrue(schema.contains("\"company\""), schema);
    assertTrue(schema.contains("\"required\": [\n    \"company\",\n    \"name\"\n  ]"), schema);
  }

  @Test
  void contentIsStyledOnlyThroughTheCatalog() {
    List<String> problems =
        problems(
            content(
                "<p style=\"color: red\">a</p><p class=\"other\">b</p><span class=\"lead\">c</span>"
                    + "<p class=\"{name}\">d</p><style>p { }</style><link rel=\"stylesheet\""
                    + " href=\"x.css\" />{#box}<p>e</p>{/box}",
                ""));
    assertEquals(
        List.of(
            "component 'box' needs the parameter 'title'",
            "no <link> in a template with a layout; the layout's stylesheets apply",
            "no <style> in a template with a layout; use the layout's style catalog; known: lead,"
                + " mark",
            "no style attributes in a template with a layout; use the layout's style catalog;"
                + " known: lead, mark",
            "'other' is not a style of the layout's catalog; known: lead, mark",
            "'lead' is a paragraph style and cannot be used on <span>",
            "class values must be written literally, not computed from data"),
        problems);
  }

  @Test
  void freeStylingKeepsHardLimits() throws Exception {
    List<String> problems =
        problems(
            content(
                "<style>@import 'x.css'; p { color: red !important; font-family: 'Comic Sans' }"
                    + " div { background: url(https://example.org/a.png) }</style>"
                    + "<p style=\"font: 12pt Roboto\" class=\"any\">x</p>",
                ", \"styling\": \"free\""));
    assertEquals(
        List.of(
            "use font-family and font-size instead of the font shorthand",
            "@import is not allowed in free CSS",
            "!important is not allowed in free CSS",
            "url() may name template assets and attachments only, not https://example.org/a.png",
            "font 'Comic Sans' is not a font of the layout; known: Roboto"),
        problems);
    // Within the limits, free CSS renders; only to PDF and XHTML.
    TemplateRepository free =
        content(
            "<style>p { font-family: 'Roboto' }</style><p style=\"color: red\">x</p>",
            ", \"styling\": \"free\"");
    assertTrue(render(free, "t.xhtml").contains("color: red"));
    assertEquals(
        java.util.EnumSet.of(OutputFormat.PDF, OutputFormat.XHTML),
        renderer.formats(free, "t.xhtml"));
  }

  @Test
  void aContentTemplateIsOneIncludeOfTheLayout() {
    MapTemplateRepository files =
        new MapTemplateRepository()
            .template(
                "t.xhtml", "<p>x</p>{#include layout}{#title}T{/title}{#side}s{/side}{/include}")
            .resource(
                "t.json", "{\"language\": \"en\", \"layout\": \"corporate@1\", \"fields\": {}}");
    assertEquals(
        List.of(
            "a template with a layout consists of one {#include layout}…{/include} that fills the"
                + " layout's areas: title, body",
            "'side' is not an area of the layout; known: title, body",
            "the layout's area 'body' must be filled"),
        problems(new ComposedTemplateRepository(files, layout())));
  }

  @Test
  void checksTheLayoutAgainstItsDescriptor() {
    MapTemplateRepository layout =
        layout()
            .template("template.xhtml", SKELETON.replace("{#insert body}", "{#insert main}"))
            .template("components/box.xhtml", "<div class=\"box\">{title}{secret}</div>")
            .resource("layout.css", ".lead { }")
            .resource("messages.json", "{}");
    List<String> problems =
        problems(
            new ComposedTemplateRepository(
                new MapTemplateRepository()
                    .template("t.xhtml", "{#include layout}{#title}T{/title}{/include}")
                    .resource(
                        "t.json",
                        "{\"language\": \"en\", \"layout\": \"corporate@1\", \"fields\": {}}"),
                layout));
    assertTrue(
        problems.contains("area 'body' has no {#insert body} in the layout"), problems::toString);
    assertTrue(
        problems.contains("{#insert main} is not an area of layout.json"), problems::toString);
    assertTrue(
        problems.contains(
            "component 'box' reads 'secret', which is not one of its parameters; components read"
                + " only their parameters"),
        problems::toString);
    assertTrue(
        problems.contains("catalog style 'mark' is not defined in the layout's stylesheets"),
        problems::toString);
    assertTrue(
        problems.contains("text 'greeting' is used but missing in messages.json"),
        problems::toString);
  }

  @Test
  void catalogStylesMustExistInTheOfficeTemplates() throws Exception {
    MapTemplateRepository layout =
        layout()
            .resource(
                "layout.dotx",
                zip(
                    "word/styles.xml",
                    "<w:styles"
                        + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:style"
                        + " w:styleId=\"Catalog-lead\"><w:name w:val=\"lead\"/></w:style>"
                        + "</w:styles>"));
    List<String> problems = problems(new ComposedTemplateRepository(files(), layout));
    assertTrue(problems.contains("catalog style 'mark' is missing"), problems::toString);
  }

  @Test
  void aLayoutIsCheckedAndPreviewedOnItsOwn() throws Exception {
    MapTemplateRepository layout = layout();
    TemplateRepository alone = new ComposedTemplateRepository(layout, layout);
    renderer.schema(alone, "template.xhtml");
    String html = render(alone, "template.xhtml");
    assertTrue(html.contains("<title>Preview</title>"), html);
  }

  @Test
  void theLayoutMustBeGivenAndNamed() {
    MapTemplateRepository files =
        new MapTemplateRepository()
            .template("t.xhtml", "{#include layout}{#title}T{/title}{#body}x{/body}{/include}")
            .resource(
                "t.json", "{\"language\": \"en\", \"layout\": \"corporate@1\", \"fields\": {}}");
    assertEquals(
        List.of(
            "the template fills the layout corporate@1, which is not available; give it with the"
                + " template (CLI: --layout)"),
        problems(files).stream().limit(1).toList());
  }

  @Test
  void fieldsMayNotBeDefinedTwice() {
    MapTemplateRepository files =
        new MapTemplateRepository()
            .template("t.xhtml", "{#include layout}{#title}T{/title}{#body}x{/body}{/include}")
            .resource(
                "t.json",
                "{\"language\": \"en\", \"layout\": \"corporate@1\","
                    + " \"fields\": {\"company\": {\"type\": \"text\"}}}");
    assertEquals(
        List.of("field 'company' is already defined by the layout"),
        problems(new ComposedTemplateRepository(files, layout())));
  }

  private static MapTemplateRepository files() {
    return new MapTemplateRepository()
        .template("t.xhtml", "{#include layout}{#title}T{/title}{#body}<p>x</p>{/body}{/include}")
        .resource("t.json", "{\"language\": \"en\", \"layout\": \"corporate@1\", \"fields\": {}}");
  }

  private static byte[] zip(String name, String content) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(bytes)) {
      out.putNextEntry(new ZipEntry(name));
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return bytes.toByteArray();
  }
}
