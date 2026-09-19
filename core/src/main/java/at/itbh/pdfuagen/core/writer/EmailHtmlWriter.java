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
import java.util.List;
import java.util.function.Function;

/**
 * HTML for email bodies: styles from the layout's {@code email.css} inlined into {@code style}
 * attributes, absolute image URLs, columns and boxes as tables with {@code role="presentation"},
 * footnotes as a linked list at the end.
 */
public final class EmailHtmlWriter {

  private final DocumentModel model;
  private final CssRules css;
  private final Function<Image, String> imageUrl;
  private final StringBuilder out = new StringBuilder();
  private final List<List<Inline>> footnotes = new ArrayList<>();

  private EmailHtmlWriter(DocumentModel model, CssRules css, Function<Image, String> imageUrl) {
    this.model = model;
    this.css = css;
    this.imageUrl = imageUrl;
  }

  /**
   * @param imageUrl absolute URL of an image as the recipient's mail client loads it
   */
  public static String write(DocumentModel model, CssRules css, Function<Image, String> imageUrl)
      throws IOException {
    return new EmailHtmlWriter(model, css, imageUrl).write();
  }

  private String write() throws IOException {
    out.append("<!DOCTYPE html>\n<html lang=\"").append(attr(model.lang())).append("\">\n<head>\n");
    out.append("<meta charset=\"utf-8\">\n");
    out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
    out.append("<title>").append(text(model.title())).append("</title>\n</head>\n");
    out.append("<body").append(style("body")).append(">\n");
    // Screen readers announce the message as an email with its title (common email practice).
    out.append("<div role=\"article\" aria-roledescription=\"email\" aria-label=\"")
        .append(attr(model.title()))
        .append("\" lang=\"")
        .append(attr(model.lang()))
        .append("\">\n");
    blocks(model.blocks());
    if (!footnotes.isEmpty()) {
      out.append("<hr")
          .append(style("hr"))
          .append(">\n<ol")
          .append(style("ol", "footnotes"))
          .append(">\n");
      for (int i = 0; i < footnotes.size(); i++) {
        int n = i + 1;
        out.append("<li id=\"fn").append(n).append('"').append(style("li", "footnote")).append('>');
        inlines(footnotes.get(i));
        out.append(" <a href=\"#fnref")
            .append(n)
            .append("\" aria-label=\"Back to text\">↩</a></li>\n");
      }
      out.append("</ol>\n");
    }
    out.append("</div>\n</body>\n</html>\n");
    return out.toString();
  }

  private void blocks(List<Block> blocks) throws IOException {
    for (Block block : blocks) {
      switch (block) {
        case Heading h -> {
          String tag = "h" + h.level();
          out.append('<').append(tag);
          if (h.id() != null) {
            out.append(" id=\"").append(attr(h.id())).append('"');
          }
          out.append(style(tag, h.style())).append('>');
          inlines(h.content());
          out.append("</").append(tag).append(">\n");
        }
        case Paragraph p -> {
          out.append("<p").append(style("p", p.style())).append('>');
          inlines(p.content());
          out.append("</p>\n");
        }
        case ListBlock list -> {
          String tag = list.ordered() ? "ol" : "ul";
          out.append('<').append(tag).append(style(tag)).append(">\n");
          for (List<Block> item : list.items()) {
            out.append("<li").append(style("li")).append('>');
            if (item.size() == 1 && item.getFirst() instanceof Paragraph p) {
              inlines(p.content());
            } else {
              blocks(item);
            }
            out.append("</li>\n");
          }
          out.append("</").append(tag).append(">\n");
        }
        case Table table -> table(table);
        case ImageBlock image -> {
          out.append("<p").append(style("p")).append('>');
          image(image.image());
          out.append("</p>\n");
        }
        case Columns columns -> {
          out.append(
                  "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
                      + " border=\"0\"")
              .append(style("table", "columns"))
              .append("><tr>\n");
          int width = 100 / Math.max(1, columns.columns().size());
          for (List<Block> column : columns.columns()) {
            out.append("<td valign=\"top\" width=\"")
                .append(width)
                .append("%\"")
                .append(style("td", "col"))
                .append(">\n");
            blocks(column);
            out.append("</td>\n");
          }
          out.append("</tr></table>\n");
        }
        case Box box -> {
          out.append(
                  "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\""
                      + " border=\"0\"><tr>")
              .append("<td")
              .append(style("td", "box", box.style()))
              .append(">\n");
          blocks(box.content());
          out.append("</td></tr></table>\n");
        }
        case PageBreak pageBreak -> {}
      }
    }
  }

  private void table(Table table) throws IOException {
    out.append("<table").append(style("table")).append(">\n");
    if (!table.caption().isEmpty()) {
      out.append("<caption").append(style("caption")).append('>');
      inlines(table.caption());
      out.append("</caption>\n");
    }
    section("thead", table.head(), true);
    section("tbody", table.body(), false);
    section("tfoot", table.foot(), false);
    out.append("</table>\n");
  }

  private void section(String tag, List<Row> rows, boolean head) throws IOException {
    if (rows.isEmpty()) {
      return;
    }
    out.append('<').append(tag).append(">\n");
    for (Row row : rows) {
      out.append("<tr>");
      for (Cell cell : row.cells()) {
        String cellTag = cell.header() ? "th" : "td";
        out.append('<').append(cellTag);
        if (cell.header()) {
          out.append(" scope=\"").append(head ? "col" : "row").append('"');
        }
        if (cell.colspan() > 1) {
          out.append(" colspan=\"").append(cell.colspan()).append('"');
        }
        out.append(style(cellTag)).append('>');
        if (cell.content().size() == 1 && cell.content().getFirst() instanceof Paragraph p) {
          inlines(p.content());
        } else {
          blocks(cell.content());
        }
        out.append("</").append(cellTag).append('>');
      }
      out.append("</tr>\n");
    }
    out.append("</").append(tag).append(">\n");
  }

  private void inlines(List<Inline> inlines) throws IOException {
    for (Inline inline : inlines) {
      switch (inline) {
        case Text t -> {
          boolean lang = t.lang() != null && !t.lang().equalsIgnoreCase(model.lang());
          boolean span = lang || t.style() != null;
          if (span) {
            out.append("<span");
            if (lang) {
              out.append(" lang=\"").append(attr(t.lang())).append('"');
            }
            out.append(style("span", t.style())).append('>');
          }
          if (t.marks().contains(Mark.STRONG)) {
            out.append("<strong>");
          }
          if (t.marks().contains(Mark.EMPHASIS)) {
            out.append("<em>");
          }
          out.append(text(t.text()));
          if (t.marks().contains(Mark.EMPHASIS)) {
            out.append("</em>");
          }
          if (t.marks().contains(Mark.STRONG)) {
            out.append("</strong>");
          }
          if (span) {
            out.append("</span>");
          }
        }
        case Link link -> {
          out.append("<a href=\"")
              .append(attr(link.href()))
              .append('"')
              .append(style("a"))
              .append('>');
          inlines(link.content());
          out.append("</a>");
        }
        case LineBreak lb -> out.append("<br>");
        case InlineImage image -> image(image.image());
        case Footnote footnote -> {
          footnotes.add(footnote.content());
          int n = footnotes.size();
          out.append("<sup><a href=\"#fn")
              .append(n)
              .append("\" id=\"fnref")
              .append(n)
              .append('"')
              .append(style("a", "footnote-ref"))
              .append('>')
              .append(n)
              .append("</a></sup>");
        }
      }
    }
  }

  private void image(Image image) throws IOException {
    Images.Raster raster = Images.raster(image);
    out.append("<img src=\"")
        .append(attr(imageUrl.apply(image)))
        .append("\" alt=\"")
        .append(attr(image.decorative() || image.alt() == null ? "" : image.alt()))
        .append('"');
    if (image.decorative()) {
      out.append(" role=\"presentation\"");
    }
    out.append(" width=\"")
        .append(raster.width())
        .append("\" height=\"")
        .append(raster.height())
        .append('"')
        .append(style("img"))
        .append('>');
  }

  private String style(String element, String... classes) {
    String declarations =
        css.style(
            element,
            java.util.Arrays.stream(classes).filter(c -> c != null).toArray(String[]::new));
    return declarations.isEmpty() ? "" : " style=\"" + attr(declarations) + "\"";
  }

  private static String text(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String attr(String value) {
    return text(value).replace("\"", "&quot;");
  }
}
