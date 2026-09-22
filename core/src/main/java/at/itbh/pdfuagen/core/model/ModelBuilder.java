/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.model;

import at.itbh.pdfuagen.core.ImageSize;
import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.model.DocumentModel.Block;
import at.itbh.pdfuagen.core.model.DocumentModel.Box;
import at.itbh.pdfuagen.core.model.DocumentModel.Cell;
import at.itbh.pdfuagen.core.model.DocumentModel.Columns;
import at.itbh.pdfuagen.core.model.DocumentModel.Footnote;
import at.itbh.pdfuagen.core.model.DocumentModel.Heading;
import at.itbh.pdfuagen.core.model.DocumentModel.Image;
import at.itbh.pdfuagen.core.model.DocumentModel.ImageBlock;
import at.itbh.pdfuagen.core.model.DocumentModel.Inline;
import at.itbh.pdfuagen.core.model.DocumentModel.InlineImage;
import at.itbh.pdfuagen.core.model.DocumentModel.LineBreak;
import at.itbh.pdfuagen.core.model.DocumentModel.Link;
import at.itbh.pdfuagen.core.model.DocumentModel.ListBlock;
import at.itbh.pdfuagen.core.model.DocumentModel.Mark;
import at.itbh.pdfuagen.core.model.DocumentModel.PageBreak;
import at.itbh.pdfuagen.core.model.DocumentModel.Paragraph;
import at.itbh.pdfuagen.core.model.DocumentModel.Row;
import at.itbh.pdfuagen.core.model.DocumentModel.Table;
import at.itbh.pdfuagen.core.model.DocumentModel.Text;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Translates rendered XHTML into a {@link DocumentModel}. Only the building blocks of the model are
 * accepted; any other element is reported as a problem, never silently approximated.
 */
public final class ModelBuilder {

  /** Loads the image a template references; records its own problems on failure. */
  @FunctionalInterface
  public interface ImageLoader {
    Optional<LoadedImage> load(String reference);
  }

  /** An image as loaded by the renderer. */
  public record LoadedImage(String source, byte[] bytes, String mediaType) {}

  /** Resolves the catalog CSS declarations that apply to an element with the given classes. */
  @FunctionalInterface
  public interface StyleSheet {
    /** e.g. {@code declarations("img", ["logo"])} → {@code "width: 50mm;"}; empty if none. */
    String declarations(String element, List<String> classes);

    StyleSheet NONE = (element, classes) -> "";
  }

  private static final Set<String> BLOCK_ELEMENTS =
      Set.of("h1", "h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "table", "div");

  private record Format(Set<Mark> marks, String lang, String style) {
    static final Format PLAIN = new Format(Set.of(), null, null);

    Format with(Mark mark) {
      Set<Mark> set = marks.isEmpty() ? EnumSet.noneOf(Mark.class) : EnumSet.copyOf(marks);
      set.add(mark);
      return new Format(Set.copyOf(set), lang, style);
    }
  }

  private final ImageLoader images;
  private final StyleSheet styles;
  private final List<Problem> problems = new ArrayList<>();

  public ModelBuilder(ImageLoader images) {
    this(images, StyleSheet.NONE);
  }

  public ModelBuilder(ImageLoader images, StyleSheet styles) {
    this.images = images;
    this.styles = styles;
  }

  public List<Problem> problems() {
    return List.copyOf(problems);
  }

  public DocumentModel build(Document document) {
    Element html = document.getDocumentElement();
    String lang = attr(html, "lang");
    if (lang.isEmpty()) {
      lang = html.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang");
    }
    String title = "";
    String description = "";
    Element body = null;
    for (Element child : children(html)) {
      switch (name(child)) {
        case "head" -> {
          for (Element meta : children(child)) {
            if (name(meta).equals("title")) {
              title = normalize(meta.getTextContent()).strip();
            } else if (name(meta).equals("meta") && attr(meta, "name").equals("description")) {
              description = attr(meta, "content");
            }
          }
        }
        case "body" -> body = child;
        default -> unsupported(child);
      }
    }
    List<Block> blocks = body == null ? List.of() : blocks(body);
    return new DocumentModel(title, lang, description, blocks);
  }

  private List<Block> blocks(Element parent) {
    List<Block> out = new ArrayList<>();
    List<Inline> pending = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && BLOCK_ELEMENTS.contains(name(element))) {
        flush(pending, out);
        block(element, out);
      } else {
        inline(node, pending, Format.PLAIN);
      }
    }
    flush(pending, out);
    return out;
  }

  private void flush(List<Inline> pending, List<Block> out) {
    List<Inline> content = tidy(pending);
    pending.clear();
    if (content.size() == 1 && content.getFirst() instanceof InlineImage image) {
      out.add(new ImageBlock(image.image()));
    } else if (!content.isEmpty()) {
      out.add(new Paragraph(content, null));
    }
  }

  private void block(Element element, List<Block> out) {
    String name = name(element);
    switch (name) {
      case "h1", "h2", "h3", "h4", "h5", "h6" ->
          out.add(
              new Heading(
                  name.charAt(1) - '0',
                  inlines(element),
                  nullIfEmpty(attr(element, "id")),
                  style(element)));
      case "p" -> out.add(new Paragraph(inlines(element), style(element)));
      case "ul", "ol" -> out.add(list(element, name.equals("ol")));
      case "table" -> out.add(table(element));
      case "div" -> div(element, out);
      default -> unsupported(element);
    }
  }

  private void div(Element element, List<Block> out) {
    String kind = attr(element, "data-block");
    switch (kind) {
      case "" -> out.addAll(blocks(element));
      case "box" -> out.add(new Box(style(element), blocks(element)));
      case "page-break" -> out.add(new PageBreak());
      case "columns" -> {
        List<List<Block>> columns = new ArrayList<>();
        for (Element child : children(element)) {
          if (name(child).equals("div") && attr(child, "data-block").equals("col")) {
            columns.add(blocks(child));
          } else {
            problem("columns may only contain data-block=\"col\" elements", child);
          }
        }
        out.add(new Columns(columns));
      }
      default -> problem("unknown data-block \"" + kind + "\"", element);
    }
  }

  private ListBlock list(Element element, boolean ordered) {
    List<List<Block>> items = new ArrayList<>();
    for (Element child : children(element)) {
      if (name(child).equals("li")) {
        items.add(blocks(child));
      } else {
        unsupported(child);
      }
    }
    return new ListBlock(ordered, items);
  }

  private Table table(Element element) {
    List<Inline> caption = List.of();
    List<Row> head = new ArrayList<>();
    List<Row> body = new ArrayList<>();
    List<Row> foot = new ArrayList<>();
    for (Element child : children(element)) {
      switch (name(child)) {
        case "caption" -> caption = inlines(child);
        case "thead" -> rows(child, head);
        case "tbody" -> rows(child, body);
        case "tfoot" -> rows(child, foot);
        case "tr" -> body.add(row(child));
        case "colgroup", "col" -> {}
        default -> unsupported(child);
      }
    }
    return new Table(caption, head, body, foot);
  }

  private void rows(Element section, List<Row> out) {
    for (Element child : children(section)) {
      if (name(child).equals("tr")) {
        out.add(row(child));
      } else {
        unsupported(child);
      }
    }
  }

  private Row row(Element tr) {
    List<Cell> cells = new ArrayList<>();
    for (Element cell : children(tr)) {
      String name = name(cell);
      if (!name.equals("td") && !name.equals("th")) {
        unsupported(cell);
        continue;
      }
      cells.add(
          new Cell(
              name.equals("th"),
              positive(attr(cell, "colspan")),
              positive(attr(cell, "rowspan")),
              blocks(cell)));
    }
    return new Row(cells);
  }

  private List<Inline> inlines(Element element) {
    List<Inline> out = new ArrayList<>();
    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
      inline(node, out, Format.PLAIN);
    }
    return tidy(out);
  }

  private void inline(Node node, List<Inline> out, Format format) {
    if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
      out.add(
          new Text(normalize(node.getNodeValue()), format.marks(), format.lang(), format.style()));
      return;
    }
    if (!(node instanceof Element element)) {
      return;
    }
    switch (name(element)) {
      case "strong", "b" -> children(element, out, format.with(Mark.STRONG));
      case "em", "i" -> children(element, out, format.with(Mark.EMPHASIS));
      case "span" -> {
        if (attr(element, "data-block").equals("footnote")) {
          List<Inline> content = new ArrayList<>();
          children(element, content, new Format(Set.of(), format.lang(), null));
          out.add(new Footnote(tidy(content)));
        } else if (!attr(element, "data-block").isEmpty()) {
          problem("unknown data-block \"" + attr(element, "data-block") + "\"", element);
        } else {
          String lang = attr(element, "lang");
          String style = style(element);
          children(
              element,
              out,
              new Format(
                  format.marks(),
                  lang.isEmpty() ? format.lang() : lang,
                  style == null ? format.style() : style));
        }
      }
      case "a" -> {
        List<Inline> content = new ArrayList<>();
        children(element, content, format);
        String href = attr(element, "href");
        if (href.isEmpty()) {
          out.addAll(content);
        } else {
          out.add(new Link(href, tidy(content)));
        }
      }
      case "br" -> out.add(new LineBreak());
      case "img" -> image(element).ifPresent(image -> out.add(new InlineImage(image)));
      default -> {
        if (BLOCK_ELEMENTS.contains(name(element))) {
          problem("<" + name(element) + "> is not allowed inside text", element);
        } else {
          unsupported(element);
        }
      }
    }
  }

  private void children(Element element, List<Inline> out, Format format) {
    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
      inline(node, out, format);
    }
  }

  private Optional<Image> image(Element element) {
    String role = attr(element, "role");
    boolean decorative =
        role.equals("presentation")
            || role.equals("none")
            || (element.hasAttribute("alt") && attr(element, "alt").isBlank());
    String alt = element.hasAttribute("alt") ? attr(element, "alt").strip() : null;
    return images
        .load(attr(element, "src"))
        .map(
            loaded -> {
              int[] size = displaySize(element, loaded);
              return new Image(
                  loaded.source(),
                  loaded.bytes(),
                  loaded.mediaType(),
                  decorative ? "" : alt,
                  decorative,
                  size == null ? null : size[0],
                  size == null ? null : size[1]);
            });
  }

  private static final Pattern LENGTH = Pattern.compile("([0-9]*\\.?[0-9]+)(px|mm|cm|in|pt|pc|q)?");

  /**
   * The display size in CSS pixels the template asks for, or {@code null} to keep the image's own
   * size. Read from the {@code style} attribute, the catalog CSS of the image's classes and the
   * {@code width}/{@code height} attributes; a missing side follows from the image's aspect ratio.
   * Non-absolute values ({@code %}, {@code auto}) are ignored.
   */
  private int[] displaySize(Element element, LoadedImage loaded) {
    List<String> classes = classes(element);
    String inline = attr(element, "style");
    String catalog = styles.declarations("img", classes);
    Double w =
        firstLength(cssValue("width", inline), cssValue("width", catalog), attr(element, "width"));
    Double h =
        firstLength(
            cssValue("height", inline), cssValue("height", catalog), attr(element, "height"));
    if (w == null && h == null) {
      return null;
    }
    if (w == null || h == null) {
      int[] intrinsic = ImageSize.of(loaded.bytes(), loaded.mediaType()).orElse(null);
      if (intrinsic == null || intrinsic[0] <= 0 || intrinsic[1] <= 0) {
        return null;
      }
      double ratio = (double) intrinsic[0] / intrinsic[1];
      if (w == null) {
        w = h * ratio;
      } else {
        h = w / ratio;
      }
    }
    return new int[] {Math.round(w.floatValue()), Math.round(h.floatValue())};
  }

  private static List<String> classes(Element element) {
    String value = attr(element, "class").strip();
    return value.isEmpty() ? List.of() : List.of(value.split("\\s+"));
  }

  /** The first value that parses as an absolute CSS length, in pixels, or {@code null}. */
  private static Double firstLength(String... values) {
    for (String value : values) {
      Double px = length(value);
      if (px != null) {
        return px;
      }
    }
    return null;
  }

  private static Double length(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    Matcher m = LENGTH.matcher(value.strip().toLowerCase(Locale.ROOT));
    if (!m.matches()) {
      return null;
    }
    double n = Double.parseDouble(m.group(1));
    String unit = m.group(2) == null ? "px" : m.group(2);
    return switch (unit) {
      case "px" -> n;
      case "mm" -> n * 96 / 25.4;
      case "cm" -> n * 96 / 2.54;
      case "in" -> n * 96;
      case "pt" -> n * 96 / 72;
      case "pc" -> n * 96 / 6;
      case "q" -> n * 96 / 25.4 / 4;
      default -> null;
    };
  }

  private static String cssValue(String property, String declarations) {
    if (declarations == null || declarations.isEmpty()) {
      return null;
    }
    Matcher m =
        Pattern.compile("(?:^|;)\\s*" + property + "\\s*:\\s*([^;]+)", Pattern.CASE_INSENSITIVE)
            .matcher(declarations);
    return m.find() ? m.group(1).strip() : null;
  }

  /** Merges adjacent runs with equal formatting and trims whitespace at the edges. */
  private static List<Inline> tidy(List<Inline> in) {
    List<Inline> merged = new ArrayList<>();
    for (Inline inline : in) {
      if (inline instanceof Text text
          && !merged.isEmpty()
          && merged.getLast() instanceof Text last
          && last.marks().equals(text.marks())
          && java.util.Objects.equals(last.lang(), text.lang())
          && java.util.Objects.equals(last.style(), text.style())) {
        merged.set(
            merged.size() - 1,
            new Text(
                (last.text() + text.text()).replaceAll(" {2,}", " "),
                last.marks(),
                last.lang(),
                last.style()));
      } else {
        merged.add(inline);
      }
    }
    // Collapse a space that follows another space or a line break across run boundaries.
    List<Inline> out = new ArrayList<>();
    boolean spaceBefore = true;
    for (Inline inline : merged) {
      if (inline instanceof Text text) {
        String value = spaceBefore ? text.text().stripLeading() : text.text();
        if (!value.isEmpty()) {
          out.add(new Text(value, text.marks(), text.lang(), text.style()));
          spaceBefore = value.endsWith(" ");
        }
      } else {
        out.add(inline);
        spaceBefore = inline instanceof LineBreak;
      }
    }
    if (!out.isEmpty() && out.getLast() instanceof Text last) {
      String value = last.text().stripTrailing();
      if (value.isEmpty()) {
        out.removeLast();
      } else {
        out.set(out.size() - 1, new Text(value, last.marks(), last.lang(), last.style()));
      }
    }
    return List.copyOf(out);
  }

  private void unsupported(Element element) {
    problem("<" + name(element) + "> is not supported in this output format", element);
  }

  private void problem(String detail, Element element) {
    problems.add(new Problem(Problem.TEMPLATE_ERROR, detail, path(element)));
  }

  private static String path(Element element) {
    StringBuilder path = new StringBuilder();
    for (Node node = element; node instanceof Element e; node = node.getParentNode()) {
      path.insert(0, "/" + name(e));
    }
    return path.toString();
  }

  private static List<Element> children(Element parent) {
    List<Element> out = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element) {
        out.add(element);
      }
    }
    return out;
  }

  private static String name(Element element) {
    String name = element.getLocalName() != null ? element.getLocalName() : element.getTagName();
    return name.toLowerCase(Locale.ROOT);
  }

  private static String attr(Element element, String name) {
    return element.getAttribute(name).strip();
  }

  private static String style(Element element) {
    String classes = attr(element, "class");
    return classes.isEmpty() ? null : classes.split("\\s+")[0];
  }

  private static String nullIfEmpty(String value) {
    return value.isEmpty() ? null : value;
  }

  private static int positive(String value) {
    try {
      return Math.max(1, Integer.parseInt(value));
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  private static String normalize(String text) {
    return text.replaceAll("\\s+", " ");
  }
}
