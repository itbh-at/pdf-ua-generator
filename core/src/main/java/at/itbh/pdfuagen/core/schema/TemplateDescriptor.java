/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import at.itbh.pdfuagen.core.OutputFormat;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.RenderException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The descriptor of a template ({@code <name>.json} next to {@code <name>.xhtml}): the language of
 * the default variant, the layouts it may be rendered with, how it is styled, the output formats it
 * offers and the field definitions.
 *
 * <pre>{@code
 * {
 *   "language": "en",
 *   "layouts": ["corporate@3", "memo@1"],
 *   "formats": ["pdf", "docx"],
 *   "fields": {
 *     "customer": { "type": "object", "label": "Customer", "fields": {
 *       "name": { "type": "text" } } },
 *     "positions": { "type": "list", "items": { "type": "object", "fields": {
 *       "price": { "type": "number" } } } }
 *   }
 * }
 * }</pre>
 *
 * <p>A single layout may also be written {@code "layout": "corporate@3"}.
 *
 * @param language language of the default variant
 * @param layouts the layout revisions the template may be rendered with, the default first; empty
 *     for a template that brings its own styling (written by developers)
 * @param styling how a template with a layout is styled
 * @param formats the output formats the template offers; all formats if the descriptor names none
 *     ({@code pdf} and {@code xhtml} for free styling)
 * @param fields the top-level fields, in definition order
 */
public record TemplateDescriptor(
    Locale language,
    List<LayoutRef> layouts,
    Styling styling,
    Set<OutputFormat> formats,
    Map<String, Field> fields) {

  /** The default layout: the first one listed, or {@code null} if the template has none. */
  public LayoutRef layout() {
    return layouts.isEmpty() ? null : layouts.getFirst();
  }

  /** How a template with a layout is styled. */
  public enum Styling {
    /** Only through the layout's style catalog and components. */
    CATALOG,
    /** Also with {@code <style>} blocks and {@code style} attributes, within hard limits. */
    FREE
  }

  /** Formats possible with free styling: only the renderers that take CSS as it is. */
  public static final Set<OutputFormat> FREE_STYLING_FORMATS =
      Collections.unmodifiableSet(EnumSet.of(OutputFormat.PDF, OutputFormat.XHTML));

  /**
   * A pinned layout revision, written {@code corporate@3}.
   *
   * @param id the layout's template id
   * @param revision the revision number
   */
  public record LayoutRef(String id, int revision) {
    private static final Pattern FORM =
        Pattern.compile("([a-z0-9][a-z0-9-]{0,63})@([1-9][0-9]{0,8})");

    /** Parses {@code corporate@3}; {@code null} if the value has another form. */
    public static LayoutRef parse(String value) {
      Matcher m = FORM.matcher(value);
      return m.matches() ? new LayoutRef(m.group(1), Integer.parseInt(m.group(2))) : null;
    }

    @Override
    public String toString() {
      return id + "@" + revision;
    }
  }

  /**
   * Parses a descriptor.
   *
   * @param name the descriptor's path, used in problem locations
   * @throws RenderException listing every problem found, as {@link Problem#TEMPLATE_ERROR}
   */
  public static TemplateDescriptor parse(byte[] json, String name) throws RenderException {
    JsonNode root = DescriptorParser.read(json, name);
    DescriptorParser p = new DescriptorParser(name);
    if (!p.object(root, "#", "the descriptor")) {
      p.failOnProblems();
    }
    p.onlyKeys(root, "#", Set.of("language", "layout", "layouts", "styling", "formats", "fields"));
    Locale language = p.language(root, "the default variant");
    List<LayoutRef> layouts = layouts(root, p);
    Styling styling = Styling.CATALOG;
    JsonNode stylingNode = root.get("styling");
    if (stylingNode != null) {
      switch (stylingNode.asText()) {
        case "catalog" -> styling = Styling.CATALOG;
        case "free" -> styling = Styling.FREE;
        default -> p.problem("#/styling", "'styling' must be \"catalog\" or \"free\"");
      }
      if (layouts.isEmpty()) {
        p.problem("#/styling", "'styling' applies only to a template with a layout");
      }
    }
    Set<OutputFormat> formats =
        styling == Styling.FREE
            ? EnumSet.copyOf(FREE_STYLING_FORMATS)
            : EnumSet.allOf(OutputFormat.class);
    JsonNode formatsNode = root.get("formats");
    if (formatsNode != null) {
      formats = p.formats(formatsNode);
      if (styling == Styling.FREE && !FREE_STYLING_FORMATS.containsAll(formats)) {
        p.problem(
            "#/formats",
            "a template with free styling offers only pdf and xhtml: DOCX, ODT, email HTML and"
                + " plain text cannot carry free CSS");
      }
    }
    Map<String, Field> fields = Map.of();
    JsonNode fieldsNode = root.get("fields");
    if (fieldsNode == null) {
      p.problem("#", "'fields' is required: the definitions of the template data");
    } else {
      fields = p.fields(fieldsNode, "#/fields");
    }
    p.failOnProblems();
    return new TemplateDescriptor(
        language, layouts, styling, Collections.unmodifiableSet(formats), fields);
  }

  /**
   * {@code "layouts"}: a non-empty list of layout revisions, at most one revision per layout; or
   * {@code "layout"}: a single one. Not both.
   */
  private static List<LayoutRef> layouts(JsonNode root, DescriptorParser p) {
    JsonNode single = root.get("layout");
    JsonNode list = root.get("layouts");
    if (single != null && list != null) {
      p.problem("#", "give either 'layout' or 'layouts', not both");
      return List.of();
    }
    if (single != null) {
      LayoutRef layout = single.isTextual() ? LayoutRef.parse(single.asText()) : null;
      if (layout == null) {
        p.problem("#/layout", "'layout' must name a layout revision, e.g. \"corporate@3\"");
        return List.of();
      }
      return List.of(layout);
    }
    if (list == null) {
      return List.of();
    }
    if (!list.isArray() || list.isEmpty()) {
      p.problem(
          "#/layouts",
          "'layouts' must be a non-empty list of layout revisions, e.g. [\"corporate@3\"]");
      return List.of();
    }
    List<LayoutRef> layouts = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < list.size(); i++) {
      JsonNode item = list.get(i);
      LayoutRef layout = item.isTextual() ? LayoutRef.parse(item.asText()) : null;
      if (layout == null) {
        p.problem("#/layouts/" + i, "must name a layout revision, e.g. \"corporate@3\"");
      } else if (!ids.add(layout.id())) {
        p.problem("#/layouts/" + i, "layout '" + layout.id() + "' is listed twice");
      } else {
        layouts.add(layout);
      }
    }
    return List.copyOf(layouts);
  }

  /** The same descriptor with other fields, e.g. combined with those of its layout. */
  public TemplateDescriptor withFields(Map<String, Field> fields) {
    return new TemplateDescriptor(language, layouts, styling, formats, fields);
  }
}
