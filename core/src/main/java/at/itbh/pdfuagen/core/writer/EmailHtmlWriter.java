/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.writer;

import at.itbh.pdfuagen.core.model.DocumentModel;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * HTML for email bodies: styles from the layout's {@code email.css} inlined into {@code style}
 * attributes, absolute image URLs, columns and boxes as tables with {@code role="presentation"},
 * footnotes as a linked list at the end. Written through the {@link Xml} StAX writer; the output is
 * served as {@code text/html}, so it carries an {@code <!DOCTYPE html>} but no XML declaration.
 */
public final class EmailHtmlWriter {

  private final DocumentModel model;
  private final CssRules css;
  private final Function<Image, String> imageUrl;
  private final Xml x = new Xml(false);
  private final List<List<Inline>> footnotes = new ArrayList<>();

  private EmailHtmlWriter(DocumentModel model, CssRules css, Function<Image, String> imageUrl) {
    this.model = model;
    this.css = css;
    this.imageUrl = imageUrl;
  }

  /**
   * @param imageUrl absolute URL of an image as the recipient's mail client loads it
   */
  public static byte[] write(DocumentModel model, CssRules css, Function<Image, String> imageUrl)
      throws IOException {
    return new EmailHtmlWriter(model, css, imageUrl).write();
  }

  private byte[] write() throws IOException {
    x.doctype("<!DOCTYPE html>");
    x.open("html", "lang", model.lang());
    x.open("head");
    x.empty("meta", "charset", "utf-8");
    x.empty("meta", "name", "viewport", "content", "width=device-width, initial-scale=1");
    x.element("title", model.title());
    x.close();
    x.open("body", "style", styleOf("body"));
    // Screen readers announce the message as an email with its title (common email practice).
    x.open(
        "div",
        "role",
        "article",
        "aria-roledescription",
        "email",
        "aria-label",
        model.title(),
        "lang",
        model.lang());
    blocks(model.blocks());
    if (!footnotes.isEmpty()) {
      x.empty("hr", "style", styleOf("hr"));
      x.open("ol", "style", styleOf("ol", "footnotes"));
      for (int i = 0; i < footnotes.size(); i++) {
        int n = i + 1;
        x.open("li", "id", "fn" + n, "style", styleOf("li", "footnote"));
        inlines(footnotes.get(i));
        x.text(" ");
        x.open("a", "href", "#fnref" + n, "aria-label", "Back to text").text("↩").close();
        x.close();
      }
      x.close();
    }
    x.close(); // div
    x.close(); // body
    x.close(); // html
    return x.bytes();
  }

  private void blocks(List<Block> blocks) throws IOException {
    for (Block block : blocks) {
      switch (block) {
        case Heading h -> {
          String tag = "h" + h.level();
          x.open(tag, "id", h.id(), "style", styleOf(tag, h.style()));
          inlines(h.content());
          x.close();
        }
        case Paragraph p -> {
          x.open("p", "style", styleOf("p", p.style()));
          inlines(p.content());
          x.close();
        }
        case ListBlock list -> {
          String tag = list.ordered() ? "ol" : "ul";
          x.open(tag, "style", styleOf(tag));
          for (List<Block> item : list.items()) {
            x.open("li", "style", styleOf("li"));
            if (item.size() == 1 && item.getFirst() instanceof Paragraph p) {
              inlines(p.content());
            } else {
              blocks(item);
            }
            x.close();
          }
          x.close();
        }
        case Table table -> table(table);
        case ImageBlock image -> {
          x.open("p", "style", styleOf("p"));
          image(image.image());
          x.close();
        }
        case Columns columns -> {
          x.open(
              "table",
              "role",
              "presentation",
              "width",
              "100%",
              "cellpadding",
              "0",
              "cellspacing",
              "0",
              "border",
              "0",
              "style",
              styleOf("table", "columns"));
          x.open("tr");
          int width = 100 / Math.max(1, columns.columns().size());
          for (List<Block> column : columns.columns()) {
            x.open("td", "valign", "top", "width", width + "%", "style", styleOf("td", "col"));
            blocks(column);
            x.close();
          }
          x.close(); // tr
          x.close(); // table
        }
        case Box box -> {
          x.open(
              "table",
              "role",
              "presentation",
              "width",
              "100%",
              "cellpadding",
              "0",
              "cellspacing",
              "0",
              "border",
              "0");
          x.open("tr");
          x.open("td", "style", styleOf("td", "box", box.style()));
          blocks(box.content());
          x.close(); // td
          x.close(); // tr
          x.close(); // table
        }
        case PageBreak pageBreak -> {}
      }
    }
  }

  private void table(Table table) throws IOException {
    x.open("table", "style", styleOf("table"));
    if (!table.caption().isEmpty()) {
      x.open("caption", "style", styleOf("caption"));
      inlines(table.caption());
      x.close();
    }
    section("thead", table.head(), true);
    section("tbody", table.body(), false);
    section("tfoot", table.foot(), false);
    x.close();
  }

  private void section(String tag, List<Row> rows, boolean head) throws IOException {
    if (rows.isEmpty()) {
      return;
    }
    x.open(tag);
    for (Row row : rows) {
      x.open("tr");
      for (Cell cell : row.cells()) {
        String cellTag = cell.header() ? "th" : "td";
        x.open(
            cellTag,
            "scope",
            cell.header() ? (head ? "col" : "row") : null,
            "colspan",
            cell.colspan() > 1 ? String.valueOf(cell.colspan()) : null,
            "style",
            styleOf(cellTag));
        if (cell.content().size() == 1 && cell.content().getFirst() instanceof Paragraph p) {
          inlines(p.content());
        } else {
          blocks(cell.content());
        }
        x.close();
      }
      x.close();
    }
    x.close();
  }

  private void inlines(List<Inline> inlines) throws IOException {
    for (Inline inline : inlines) {
      switch (inline) {
        case Text t -> {
          boolean lang = t.lang() != null && !t.lang().equalsIgnoreCase(model.lang());
          boolean span = lang || t.style() != null;
          if (span) {
            x.open("span", "lang", lang ? t.lang() : null, "style", styleOf("span", t.style()));
          }
          if (t.marks().contains(Mark.STRONG)) {
            x.open("strong");
          }
          if (t.marks().contains(Mark.EMPHASIS)) {
            x.open("em");
          }
          x.text(t.text());
          if (t.marks().contains(Mark.EMPHASIS)) {
            x.close();
          }
          if (t.marks().contains(Mark.STRONG)) {
            x.close();
          }
          if (span) {
            x.close();
          }
        }
        case Link link -> {
          x.open("a", "href", link.href(), "style", styleOf("a"));
          inlines(link.content());
          x.close();
        }
        case LineBreak lb -> x.empty("br");
        case InlineImage image -> image(image.image());
        case Footnote footnote -> {
          footnotes.add(footnote.content());
          int n = footnotes.size();
          x.open("sup");
          x.open("a", "href", "#fn" + n, "id", "fnref" + n, "style", styleOf("a", "footnote-ref"))
              .text(String.valueOf(n))
              .close();
          x.close();
        }
      }
    }
  }

  private void image(Image image) throws IOException {
    Images.Raster raster = Images.raster(image);
    x.empty(
        "img",
        "src",
        imageUrl.apply(image),
        "alt",
        image.decorative() || image.alt() == null ? "" : image.alt(),
        "role",
        image.decorative() ? "presentation" : null,
        "width",
        String.valueOf(raster.width()),
        "height",
        String.valueOf(raster.height()),
        "style",
        styleOf("img"));
  }

  /** The declarations for {@code element} and {@code classes}, or {@code null} when empty. */
  private String styleOf(String element, String... classes) {
    String declarations =
        css.style(element, Arrays.stream(classes).filter(Objects::nonNull).toArray(String[]::new));
    return declarations.isEmpty() ? null : declarations;
  }
}
