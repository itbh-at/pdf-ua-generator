/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.model;

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
  private final List<Problem> problems = new ArrayList<>();

  public ModelBuilder(ImageLoader images) {
    this.images = images;
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
      if (positive(attr(cell, "rowspan")) > 1) {
        problem("rowspan is not supported", cell);
      }
      cells.add(new Cell(name.equals("th"), positive(attr(cell, "colspan")), blocks(cell)));
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
            loaded ->
                new Image(
                    loaded.source(),
                    loaded.bytes(),
                    loaded.mediaType(),
                    decorative ? "" : alt,
                    decorative));
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
