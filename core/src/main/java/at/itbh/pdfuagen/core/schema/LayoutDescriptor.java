/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import at.itbh.pdfuagen.core.RenderException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The descriptor of a layout ({@code layout.json}): what content templates may use, and what the
 * layout itself reads from the data.
 *
 * <pre>{@code
 * {
 *   "language": "en",
 *   "styles": {
 *     "lead": { "label": "Lead paragraph", "kind": "paragraph" },
 *     "highlight": { "label": "Highlight", "kind": "character" }
 *   },
 *   "classes": ["logo", "columns", "col", "box"],
 *   "areas": { "title": { "required": true }, "body": { "required": true } },
 *   "components": {
 *     "box": { "label": "Box", "parameters": { "title": { "required": false } } }
 *   },
 *   "fonts": ["Roboto"],
 *   "freeStyling": false,
 *   "fields": { "recipient": { "type": "text" } }
 * }
 * }</pre>
 *
 * @param language language of the layout's default texts ({@code messages.json})
 * @param styles the style catalog: CSS classes content templates may use, by name
 * @param classes further classes the layout and its components use; not offered to content
 * @param areas the areas a content template fills, as {@code {#insert}} names of the layout
 * @param components the components (Qute user tags) in {@code components/<name>.xhtml}
 * @param fonts font families a template with free styling may name
 * @param freeStyling whether content templates may opt into free styling
 * @param fields data the layout itself reads, e.g. a recipient in the letterhead
 */
public record LayoutDescriptor(
    Locale language,
    Map<String, Style> styles,
    List<String> classes,
    Map<String, Area> areas,
    Map<String, Component> components,
    List<String> fonts,
    boolean freeStyling,
    Map<String, Field> fields) {

  /** File name of the descriptor, at the root of a layout. */
  public static final String FILE = "layout.json";

  /** Where a style may be used. */
  public enum StyleKind {
    /** On block elements: paragraphs, headings, list items, table cells. */
    PARAGRAPH,
    /** On inline elements: {@code span}, {@code strong}, {@code em}, {@code a}. */
    CHARACTER
  }

  public record Style(String label, String description, StyleKind kind) {}

  public record Area(String label, String description, boolean required) {}

  public record Component(String label, String description, Map<String, Parameter> parameters) {}

  public record Parameter(String label, String description, boolean required) {}

  /**
   * @param name the descriptor's path, used in problem locations
   */
  public static LayoutDescriptor parse(byte[] json, String name) throws RenderException {
    JsonNode root = DescriptorParser.read(json, name);
    DescriptorParser p = new DescriptorParser(name);
    if (!p.object(root, "#", "the layout descriptor")) {
      p.failOnProblems();
    }
    p.onlyKeys(
        root,
        "#",
        Set.of(
            "language",
            "styles",
            "classes",
            "areas",
            "components",
            "fonts",
            "freeStyling",
            "fields"));
    Locale language = p.language(root, "the layout's default texts");
    Map<String, Style> styles =
        root.has("styles")
            ? p.named(
                root.get("styles"),
                "#/styles",
                "style",
                DescriptorParser.CLASS_NAME,
                (node, pointer) -> style(p, node, pointer),
                "use a CSS class name")
            : Map.of();
    List<String> classes =
        root.has("classes")
            ? p.strings(root.get("classes"), "#/classes", DescriptorParser.CLASS_NAME, "class name")
            : List.of();
    for (String c : classes) {
      if (styles.containsKey(c)) {
        p.problem("#/classes", "'" + c + "' is a catalog style; list it only under 'styles'");
      }
    }
    Map<String, Area> areas =
        root.has("areas")
            ? p.named(
                root.get("areas"),
                "#/areas",
                "area",
                DescriptorParser.NAME,
                (node, pointer) -> area(p, node, pointer),
                "use letters, digits and '_'")
            : Map.of();
    if (areas.isEmpty()) {
      p.problem("#", "'areas' is required: the {#insert} areas content templates fill");
    }
    Map<String, Component> components =
        root.has("components")
            ? p.named(
                root.get("components"),
                "#/components",
                "component",
                DescriptorParser.NAME,
                (node, pointer) -> component(p, node, pointer),
                "use letters, digits and '_'")
            : Map.of();
    List<String> fonts =
        root.has("fonts")
            ? p.strings(root.get("fonts"), "#/fonts", null, "font family")
            : List.of();
    boolean freeStyling = p.bool(root, "freeStyling", "#", false);
    Map<String, Field> fields =
        root.has("fields") ? p.fields(root.get("fields"), "#/fields") : Map.of();
    p.failOnProblems();
    return new LayoutDescriptor(
        language, styles, classes, areas, components, fonts, freeStyling, fields);
  }

  private static Style style(DescriptorParser p, JsonNode node, String pointer) {
    if (!p.object(node, pointer, "a style")) {
      return null;
    }
    p.onlyKeys(node, pointer, Set.of("label", "description", "kind"));
    StyleKind kind = StyleKind.PARAGRAPH;
    String value = p.text(node, "kind", pointer);
    if (value != null) {
      switch (value) {
        case "paragraph" -> kind = StyleKind.PARAGRAPH;
        case "character" -> kind = StyleKind.CHARACTER;
        default -> p.problem(pointer + "/kind", "'kind' must be \"paragraph\" or \"character\"");
      }
    }
    return new Style(p.text(node, "label", pointer), p.text(node, "description", pointer), kind);
  }

  private static Area area(DescriptorParser p, JsonNode node, String pointer) {
    if (!p.object(node, pointer, "an area")) {
      return null;
    }
    p.onlyKeys(node, pointer, Set.of("label", "description", "required"));
    return new Area(
        p.text(node, "label", pointer),
        p.text(node, "description", pointer),
        p.bool(node, "required", pointer, false));
  }

  private static Component component(DescriptorParser p, JsonNode node, String pointer) {
    if (!p.object(node, pointer, "a component")) {
      return null;
    }
    p.onlyKeys(node, pointer, Set.of("label", "description", "parameters"));
    Map<String, Parameter> parameters =
        node.has("parameters")
            ? p.named(
                node.get("parameters"),
                pointer + "/parameters",
                "parameter",
                DescriptorParser.NAME,
                (n, ptr) -> parameter(p, n, ptr),
                "use letters, digits and '_'")
            : Map.of();
    return new Component(
        p.text(node, "label", pointer), p.text(node, "description", pointer), parameters);
  }

  private static Parameter parameter(DescriptorParser p, JsonNode node, String pointer) {
    if (!p.object(node, pointer, "a parameter")) {
      return null;
    }
    p.onlyKeys(node, pointer, Set.of("label", "description", "required"));
    return new Parameter(
        p.text(node, "label", pointer),
        p.text(node, "description", pointer),
        p.bool(node, "required", pointer, false));
  }
}
