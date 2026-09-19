/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.schema;

import at.itbh.pdfuagen.core.Messages;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.TemplateRepository;
import at.itbh.pdfuagen.core.schema.LayoutDescriptor.StyleKind;
import at.itbh.pdfuagen.core.schema.TemplateDescriptor.Styling;
import io.quarkus.qute.IncludeSectionHelper;
import io.quarkus.qute.InsertSectionHelper;
import io.quarkus.qute.SectionBlock;
import io.quarkus.qute.SectionNode;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateNode;
import io.quarkus.qute.UserTagSectionHelper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The rules of layouts and of the content templates that fill them.
 *
 * <p>Content is checked on its source. Data is always escaped, so markup can only come from the
 * literal text of a template: what the source does not contain, the rendered document cannot
 * either.
 */
public final class LayoutRules {

  /** Where the layout's files are, relative to the repository of a content template. */
  public static final String PREFIX = "layout/";

  /** The layout's template, filled by content templates with {@code {#include layout}}. */
  public static final String TEMPLATE = "template.xhtml";

  public static final String DOTX = "layout.dotx";
  public static final String OTT = "layout.ott";

  /** Section names Qute reserves; a component cannot take them. */
  private static final Set<String> RESERVED =
      Set.of(
          "if",
          "else",
          "for",
          "each",
          "let",
          "set",
          "with",
          "when",
          "switch",
          "is",
          "case",
          "include",
          "insert",
          "fragment",
          "capture",
          "eval",
          "cache");

  private static final Set<String> CHARACTER_ELEMENTS =
      Set.of("span", "a", "strong", "em", "b", "i", "abbr", "code", "small", "sub", "sup");

  private static final Pattern TAG =
      Pattern.compile("<([A-Za-z][\\w:-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>");
  private static final Pattern CLASS_ATTR =
      Pattern.compile("\\sclass\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
  private static final Pattern STYLE_ATTR =
      Pattern.compile("\\sstyle\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
  private static final Pattern STYLE_ELEMENT =
      Pattern.compile("<style\\b[^>]*>(.*?)</style\\s*>", Pattern.DOTALL);
  private static final Pattern LINK = Pattern.compile("<link\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern STYLESHEET_HREF =
      Pattern.compile("<link\\b[^>]*\\shref\\s*=\\s*[\"']([^\"'{]+\\.css)[\"']");
  private static final Pattern URL = Pattern.compile("url\\(\\s*['\"]?([^'\")]*)");
  private static final Pattern FONT_FAMILY = Pattern.compile("font-family\\s*:\\s*([^;}]*)");
  private static final Pattern FONT_SHORTHAND = Pattern.compile("(?<![\\w-])font\\s*:");

  private LayoutRules() {}

  /**
   * @param problems what prevents rendering or publishing
   * @param warnings what does not
   */
  public record Findings(List<Problem> problems, List<String> warnings) {}

  /**
   * Checks a layout against its descriptor: every area is an {@code {#insert}}, every component
   * exists and reads only its parameters, every catalog style exists in the layout's stylesheets
   * and in its {@code .dotx} and {@code .ott}, every text it uses exists in {@code messages.json}.
   *
   * @param repository a repository with the layout under {@link #PREFIX}
   * @param templates parses a template by id
   */
  public static Findings checkLayout(
      LayoutDescriptor layout,
      TemplateRepository repository,
      Function<String, Optional<Template>> templates) {
    List<Problem> problems = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    String layoutId = PREFIX + TEMPLATE;
    String descriptor = PREFIX + LayoutDescriptor.FILE;
    Optional<Template> skeleton = templates.apply(layoutId);
    Set<String> keys = new LinkedHashSet<>();
    if (skeleton.isPresent()) {
      Map<String, TemplateNode.Origin> inserts = new LinkedHashMap<>();
      inserts(skeleton.get().getNodes(), inserts);
      for (String area : layout.areas().keySet()) {
        if (!inserts.containsKey(area)) {
          problems.add(
              problem("area '" + area + "' has no {#insert " + area + "} in the layout", layoutId));
        }
      }
      inserts.forEach(
          (name, origin) -> {
            if (!layout.areas().containsKey(name)) {
              problems.add(
                  problem(
                      "{#insert " + name + "} is not an area of " + LayoutDescriptor.FILE,
                      UsageScanner.location(origin)));
            }
          });
      UsageScanner.scan(skeleton.get(), templates).messages().forEach(m -> keys.add(m.key()));
    }

    layout
        .components()
        .forEach(
            (name, component) -> {
              String id = PREFIX + "components/" + name + ".xhtml";
              if (RESERVED.contains(name)) {
                problems.add(
                    problem("component name '" + name + "' is reserved by Qute", descriptor));
                return;
              }
              Optional<Template> template = templates.apply(id);
              if (template.isEmpty()) {
                return;
              }
              UsageScanner.Result scan = UsageScanner.scan(template.get(), templates);
              scan.messages().forEach(m -> keys.add(m.key()));
              Set<String> allowed = new LinkedHashSet<>(component.parameters().keySet());
              allowed.addAll(Set.of("it", "nested-content", "_args"));
              for (UsageScanner.Usage usage : scan.usages()) {
                if (!usage.steps().isEmpty()
                    && usage.steps().getFirst() instanceof UsageScanner.Step.Prop p
                    && !allowed.contains(p.name())) {
                  problems.add(
                      problem(
                          "component '"
                              + name
                              + "' reads '"
                              + p.name()
                              + "', which is not one of its parameters; components read only"
                              + " their parameters",
                          usage.location()));
                }
              }
            });

    String css = stylesheets(repository, skeleton.flatMap(t -> repository.template(layoutId)));
    for (String style : layout.styles().keySet()) {
      if (!Pattern.compile("\\." + Pattern.quote(style) + "(?![\\w-])").matcher(css).find()) {
        problems.add(
            problem(
                "catalog style '" + style + "' is not defined in the layout's stylesheets",
                descriptor));
      }
    }
    officeStyles(repository, PREFIX + DOTX, layout, problems);
    officeStyles(repository, PREFIX + OTT, layout, problems);

    if (!keys.isEmpty()) {
      try {
        Optional<Map<String, String>> texts =
            Messages.read(repository, PREFIX + Messages.DEFAULT_FILE);
        if (texts.isEmpty()) {
          problems.add(
              problem(
                  "the layout uses texts ({msg:…}) but has no " + Messages.DEFAULT_FILE,
                  descriptor));
        } else {
          for (String key : keys) {
            if (!texts.get().containsKey(key)) {
              problems.add(
                  problem(
                      "text '" + key + "' is used but missing in " + Messages.DEFAULT_FILE,
                      PREFIX + Messages.DEFAULT_FILE));
            }
          }
        }
      } catch (IOException e) {
        problems.add(problem(e.getMessage(), PREFIX + Messages.DEFAULT_FILE));
      }
    }
    return new Findings(problems, warnings);
  }

  /**
   * Checks the texts of a layout for the languages a template is rendered in: a translation file
   * must not have keys the default file lacks; missing translations are warnings.
   */
  public static Findings checkTexts(
      TemplateRepository repository, LayoutDescriptor layout, Set<Locale> languages) {
    List<Problem> problems = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> defaults;
    try {
      defaults = Messages.read(repository, PREFIX + Messages.DEFAULT_FILE).orElse(Map.of());
    } catch (IOException e) {
      return new Findings(
          List.of(problem(e.getMessage(), PREFIX + Messages.DEFAULT_FILE)), warnings);
    }
    if (defaults.isEmpty()) {
      return new Findings(problems, warnings);
    }
    for (Locale language : languages) {
      if (language.getLanguage().equals(layout.language().getLanguage())) {
        continue;
      }
      Set<String> covered = new LinkedHashSet<>();
      for (String file : Messages.files(language)) {
        if (file.equals(Messages.DEFAULT_FILE)) {
          break;
        }
        try {
          Optional<Map<String, String>> texts = Messages.read(repository, PREFIX + file);
          if (texts.isPresent()) {
            for (String key : texts.get().keySet()) {
              if (!defaults.containsKey(key)) {
                problems.add(
                    problem(
                        "text '" + key + "' is not in " + Messages.DEFAULT_FILE, PREFIX + file));
              }
            }
            covered.addAll(texts.get().keySet());
          }
        } catch (IOException e) {
          problems.add(problem(e.getMessage(), PREFIX + file));
        }
      }
      List<String> missing = defaults.keySet().stream().filter(k -> !covered.contains(k)).toList();
      if (!missing.isEmpty()) {
        warnings.add(
            "the layout has no "
                + language.toLanguageTag()
                + " text for "
                + String.join(", ", missing)
                + "; the default texts are used");
      }
    }
    return new Findings(problems, warnings);
  }

  /**
   * Checks a content template against its layout.
   *
   * @param descriptorPath the content descriptor's path, for problem locations
   * @param ids the default variant and the language variants
   */
  public static Findings checkContent(
      LayoutDescriptor layout,
      TemplateDescriptor descriptor,
      String descriptorPath,
      List<String> ids,
      TemplateRepository repository,
      Function<String, Optional<Template>> templates) {
    List<Problem> problems = new ArrayList<>();
    boolean free = descriptor.styling() == Styling.FREE;
    if (free && !layout.freeStyling()) {
      problems.add(
          problem(
              "the layout does not allow free styling; use its style catalog",
              descriptorPath + "#/styling"));
    }
    Set<String> sources = new LinkedHashSet<>();
    for (String id : ids) {
      Optional<Template> template = templates.apply(id);
      if (template.isEmpty()) {
        continue;
      }
      structure(template.get(), id, layout, problems);
      sources.add(id);
      includes(template.get().getNodes(), templates, sources);
    }
    for (String id : sources) {
      Optional<Template> template = templates.apply(id);
      if (template.isEmpty()) {
        continue;
      }
      UsageScanner.Result scan = UsageScanner.scan(template.get(), templates);
      for (UsageScanner.Call call : scan.calls()) {
        LayoutDescriptor.Component component = layout.components().get(call.component());
        if (component == null) {
          continue;
        }
        for (String parameter : call.parameters()) {
          if (!component.parameters().containsKey(parameter)) {
            problems.add(
                problem(
                    "component '"
                        + call.component()
                        + "' has no parameter '"
                        + parameter
                        + "'"
                        + known(component.parameters().keySet()),
                    call.location()));
          }
        }
        component
            .parameters()
            .forEach(
                (name, parameter) -> {
                  if (parameter.required() && !call.parameters().contains(name)) {
                    problems.add(
                        problem(
                            "component '"
                                + call.component()
                                + "' needs the parameter '"
                                + name
                                + "'",
                            call.location()));
                  }
                });
      }
      repository.template(id).ifPresent(source -> source(source, id, layout, free, problems));
    }
    return new Findings(problems, List.of());
  }

  /** A content template is one {@code {#include layout}} whose blocks fill declared areas. */
  private static void structure(
      Template template, String id, LayoutDescriptor layout, List<Problem> problems) {
    SectionNode include = null;
    boolean other = false;
    for (TemplateNode node : template.getNodes()) {
      if (node.isText() && node.asText().getValue().replaceAll("<!--.*?-->", "").isBlank()) {
        continue;
      }
      if (include == null
          && node.isSection()
          && node.asSection().getHelper() instanceof IncludeSectionHelper
          && !(node.asSection().getHelper() instanceof UserTagSectionHelper)
          && "layout".equals(node.asSection().getBlocks().getFirst().parameters.get("template"))) {
        include = node.asSection();
      } else {
        other = true;
      }
    }
    if (include == null || other) {
      problems.add(
          problem(
              "a template with a layout consists of one {#include layout}…{/include} that fills"
                  + " the layout's areas: "
                  + String.join(", ", layout.areas().keySet()),
              id));
      if (include == null) {
        return;
      }
    }
    Set<String> filled = new LinkedHashSet<>();
    for (SectionBlock block : include.getBlocks().subList(1, include.getBlocks().size())) {
      if (!layout.areas().containsKey(block.label)) {
        problems.add(
            problem(
                "'"
                    + block.label
                    + "' is not an area of the layout"
                    + known(layout.areas().keySet()),
                UsageScanner.location(block.origin)));
      } else if (!filled.add(block.label)) {
        problems.add(
            problem(
                "area '" + block.label + "' is filled twice", UsageScanner.location(block.origin)));
      }
    }
    layout
        .areas()
        .forEach(
            (name, area) -> {
              if (area.required() && !filled.contains(name)) {
                problems.add(problem("the layout's area '" + name + "' must be filled", id));
              }
            });
  }

  /** Styling rules on the source of a content template. */
  private static void source(
      String source, String id, LayoutDescriptor layout, boolean free, List<Problem> problems) {
    Matcher link = LINK.matcher(source);
    while (link.find()) {
      problems.add(
          problem(
              "no <link> in a template with a layout; the layout's stylesheets apply",
              at(id, source, link.start())));
    }
    if (!free) {
      Matcher style = STYLE_ELEMENT.matcher(source);
      while (style.find()) {
        problems.add(
            problem(
                "no <style> in a template with a layout; use the layout's style catalog"
                    + known(layout.styles().keySet()),
                at(id, source, style.start())));
      }
    }
    Matcher tag = TAG.matcher(source);
    while (tag.find()) {
      String element = tag.group(1).toLowerCase(Locale.ROOT);
      String attributes = tag.group(2);
      int offset = tag.start();
      Matcher style = STYLE_ATTR.matcher(attributes);
      if (style.find()) {
        if (!free) {
          problems.add(
              problem(
                  "no style attributes in a template with a layout; use the layout's style"
                      + " catalog"
                      + known(layout.styles().keySet()),
                  at(id, source, offset)));
        } else {
          css(
              style.group(1) != null ? style.group(1) : style.group(2),
              id,
              source,
              offset,
              layout,
              problems);
        }
      }
      if (free) {
        continue;
      }
      Matcher classes = CLASS_ATTR.matcher(attributes);
      if (classes.find()) {
        String value = classes.group(1) != null ? classes.group(1) : classes.group(2);
        if (value.contains("{")) {
          problems.add(
              problem(
                  "class values must be written literally, not computed from data",
                  at(id, source, offset)));
          continue;
        }
        for (String name : value.strip().split("\\s+")) {
          if (name.isEmpty()) {
            continue;
          }
          LayoutDescriptor.Style catalog = layout.styles().get(name);
          if (catalog == null) {
            problems.add(
                problem(
                    "'"
                        + name
                        + "' is not a style of the layout's catalog"
                        + known(layout.styles().keySet()),
                    at(id, source, offset)));
          } else if ((catalog.kind() == StyleKind.CHARACTER)
              != CHARACTER_ELEMENTS.contains(element)) {
            problems.add(
                problem(
                    "'"
                        + name
                        + "' is a "
                        + catalog.kind().name().toLowerCase(Locale.ROOT)
                        + " style and cannot be used on <"
                        + element
                        + ">",
                    at(id, source, offset)));
          }
        }
      }
    }
    if (free) {
      Matcher style = STYLE_ELEMENT.matcher(source);
      while (style.find()) {
        css(style.group(1), id, source, style.start(1), layout, problems);
      }
    }
  }

  /** The hard limits of free CSS. */
  private static void css(
      String css,
      String id,
      String source,
      int offset,
      LayoutDescriptor layout,
      List<Problem> problems) {
    String location = at(id, source, offset);
    if (css.contains("@import")) {
      problems.add(problem("@import is not allowed in free CSS", location));
    }
    if (css.contains("!important")) {
      problems.add(problem("!important is not allowed in free CSS", location));
    }
    if (css.contains("@font-face")) {
      problems.add(
          problem("@font-face is not allowed in free CSS; use the layout's fonts", location));
    }
    if (FONT_SHORTHAND.matcher(css).find()) {
      problems.add(
          problem("use font-family and font-size instead of the font shorthand", location));
    }
    Matcher url = URL.matcher(css);
    while (url.find()) {
      String target = url.group(1).strip();
      if (target.contains(":") && !target.startsWith("attachment:")) {
        problems.add(
            problem(
                "url() may name template assets and attachments only, not " + target, location));
      }
    }
    Matcher family = FONT_FAMILY.matcher(css);
    while (family.find()) {
      for (String name : family.group(1).split(",")) {
        String font = name.strip().replaceAll("^[\"']|[\"']$", "");
        if (!font.isEmpty() && !layout.fonts().contains(font)) {
          problems.add(
              problem(
                  "font '" + font + "' is not a font of the layout" + known(layout.fonts()),
                  location));
        }
      }
    }
  }

  /** The text of the layout's stylesheets: {@code <style>} of its template and linked files. */
  private static String stylesheets(TemplateRepository repository, Optional<String> skeleton) {
    StringBuilder css = new StringBuilder();
    skeleton.ifPresent(
        source -> {
          Matcher style = STYLE_ELEMENT.matcher(source);
          while (style.find()) {
            css.append(style.group(1)).append('\n');
          }
          Matcher href = STYLESHEET_HREF.matcher(source);
          while (href.find()) {
            repository
                .resource(href.group(1))
                .ifPresent(b -> css.append(new String(b, java.nio.charset.StandardCharsets.UTF_8)));
          }
        });
    return css.toString();
  }

  /** Catalog styles must exist, by name, in the layout's Word and ODF templates if it has them. */
  private static void officeStyles(
      TemplateRepository repository, String path, LayoutDescriptor layout, List<Problem> problems) {
    Optional<byte[]> file = repository.resource(path);
    if (file.isEmpty()) {
      return;
    }
    Set<String> names;
    try {
      names =
          path.endsWith(".dotx")
              ? styleNames(file.get(), "word/styles.xml", "name", "val")
              : styleNames(file.get(), "styles.xml", "style", "display-name", "name");
    } catch (IOException | XMLStreamException e) {
      problems.add(problem("cannot read the styles: " + e.getMessage(), path));
      return;
    }
    for (String style : layout.styles().keySet()) {
      if (!names.contains(style)) {
        problems.add(problem("catalog style '" + style + "' is missing", path));
      }
    }
  }

  /**
   * Style names in an office template: for DOCX {@code <w:name w:val>} of each style, for ODT the
   * {@code style:display-name}, else {@code style:name}, of each {@code style:style}.
   */
  private static Set<String> styleNames(
      byte[] zip, String part, String element, String... attributes)
      throws IOException, XMLStreamException {
    byte[] xml = null;
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        if (entry.getName().equals(part)) {
          xml = in.readNBytes(50 * 1024 * 1024);
        }
      }
    }
    if (xml == null) {
      throw new IOException(part + " not found");
    }
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    Set<String> names = new LinkedHashSet<>();
    while (reader.hasNext()) {
      if (reader.next() == XMLStreamReader.START_ELEMENT && reader.getLocalName().equals(element)) {
        for (String attribute : attributes) {
          String value = attribute(reader, attribute);
          if (value != null) {
            names.add(value);
            break;
          }
        }
      }
    }
    return names;
  }

  private static String attribute(XMLStreamReader reader, String localName) {
    for (int i = 0; i < reader.getAttributeCount(); i++) {
      if (reader.getAttributeLocalName(i).equals(localName)) {
        return reader.getAttributeValue(i);
      }
    }
    return null;
  }

  /** The content's own templates included from these nodes, transitively; not the layout. */
  private static void includes(
      List<TemplateNode> nodes, Function<String, Optional<Template>> templates, Set<String> out) {
    for (TemplateNode node : nodes) {
      if (!node.isSection()) {
        continue;
      }
      SectionNode section = node.asSection();
      if (section.getHelper() instanceof IncludeSectionHelper
          && !(section.getHelper() instanceof UserTagSectionHelper)) {
        String id = section.getBlocks().getFirst().parameters.get("template");
        if (id != null && !id.equals("layout") && !id.startsWith(PREFIX) && out.add(id)) {
          templates.apply(id).ifPresent(t -> includes(t.getNodes(), templates, out));
        }
      }
      for (SectionBlock block : section.getBlocks()) {
        includes(block.nodes, templates, out);
      }
    }
  }

  private static void inserts(List<TemplateNode> nodes, Map<String, TemplateNode.Origin> out) {
    for (TemplateNode node : nodes) {
      if (node.isSection()) {
        SectionNode section = node.asSection();
        if (section.getHelper() instanceof InsertSectionHelper) {
          String name = section.getBlocks().getFirst().parameters.get("name");
          if (name != null) {
            out.putIfAbsent(name, section.getOrigin());
          }
        }
        for (SectionBlock block : section.getBlocks()) {
          inserts(block.nodes, out);
        }
      }
    }
  }

  private static String known(java.util.Collection<String> names) {
    return names.isEmpty() ? "" : "; known: " + String.join(", ", names);
  }

  /** {@code id, line L, column C} of an offset in a source. */
  private static String at(String id, String source, int offset) {
    int line = 1;
    int lineStart = 0;
    for (int i = 0; i < offset && i < source.length(); i++) {
      if (source.charAt(i) == '\n') {
        line++;
        lineStart = i + 1;
      }
    }
    return id + ", line " + line + ", column " + (offset - lineStart + 1);
  }

  private static Problem problem(String detail, String location) {
    return new Problem(Problem.TEMPLATE_ERROR, detail, location);
  }
}
