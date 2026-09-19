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
import at.itbh.pdfuagen.core.model.DocumentModel.ImageBlock;
import at.itbh.pdfuagen.core.model.DocumentModel.Inline;
import at.itbh.pdfuagen.core.model.DocumentModel.InlineImage;
import at.itbh.pdfuagen.core.model.DocumentModel.LineBreak;
import at.itbh.pdfuagen.core.model.DocumentModel.Link;
import at.itbh.pdfuagen.core.model.DocumentModel.ListBlock;
import at.itbh.pdfuagen.core.model.DocumentModel.PageBreak;
import at.itbh.pdfuagen.core.model.DocumentModel.Paragraph;
import at.itbh.pdfuagen.core.model.DocumentModel.Row;
import at.itbh.pdfuagen.core.model.DocumentModel.Table;
import at.itbh.pdfuagen.core.model.DocumentModel.Text;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain text: headings underlined, lists with {@code -} or numbers, tables as aligned columns,
 * links as {@code text <url>}, images as their alt text, footnotes numbered and collected at the
 * end. Lines are wrapped at 72 characters.
 */
public final class TextWriter {

  static final int WIDTH = 72;

  private final StringBuilder out = new StringBuilder();
  private final List<List<Inline>> footnotes = new ArrayList<>();

  private TextWriter() {}

  public static String write(DocumentModel model) {
    TextWriter writer = new TextWriter();
    writer.blocks(model.blocks(), "");
    if (!writer.footnotes.isEmpty()) {
      writer.out.append("----\n\n");
      for (int i = 0; i < writer.footnotes.size(); i++) {
        writer.paragraph("[" + (i + 1) + "] " + writer.text(writer.footnotes.get(i)), "", "    ");
      }
    }
    String text = writer.out.toString().replaceAll("\n{3,}", "\n\n").strip();
    return text + "\n";
  }

  private void blocks(List<Block> blocks, String prefix) {
    for (Block block : blocks) {
      switch (block) {
        case Heading heading -> {
          String text = text(heading.content());
          out.append(prefix).append(text).append('\n');
          if (heading.level() <= 2) {
            out.append(prefix)
                .append(String.valueOf(heading.level() == 1 ? '=' : '-').repeat(text.length()))
                .append('\n');
          }
          out.append('\n');
        }
        case Paragraph paragraph -> paragraph(text(paragraph.content()), prefix, prefix);
        case ListBlock list -> {
          int number = 1;
          for (List<Block> item : list.items()) {
            String marker = list.ordered() ? (number++) + ". " : "- ";
            int start = out.length();
            blocks(item, prefix + " ".repeat(marker.length()));
            // Put the marker in place of the first indentation of the item.
            int at = start + prefix.length();
            if (at + marker.length() <= out.length()) {
              out.replace(at, at + marker.length(), marker);
            }
            trimBlankLine();
          }
          out.append('\n');
        }
        case Table table -> table(table, prefix);
        case ImageBlock image -> {
          if (!image.image().decorative()) {
            paragraph("[" + image.image().alt() + "]", prefix, prefix);
          }
        }
        case Columns columns -> columns.columns().forEach(column -> blocks(column, prefix));
        case Box box -> blocks(box.content(), prefix + "| ");
        case PageBreak pageBreak -> out.append('\n');
      }
    }
  }

  private void trimBlankLine() {
    if (out.length() >= 2
        && out.charAt(out.length() - 1) == '\n'
        && out.charAt(out.length() - 2) == '\n') {
      out.setLength(out.length() - 1);
    }
  }

  private void table(Table table, String prefix) {
    String caption = text(table.caption());
    if (!caption.isEmpty()) {
      out.append(prefix).append(caption).append("\n\n");
    }
    List<List<String>> rows = new ArrayList<>();
    for (List<Row> section : List.of(table.head(), table.body(), table.foot())) {
      for (Row row : section) {
        List<String> cells = new ArrayList<>();
        for (Cell cell : row.cells()) {
          StringBuilder text = new StringBuilder();
          for (Block block : cell.content()) {
            if (block instanceof Paragraph p) {
              text.append(text.isEmpty() ? "" : " ").append(text(p.content()));
            }
          }
          cells.add(text.toString());
          for (int i = 1; i < cell.colspan(); i++) {
            cells.add("");
          }
        }
        rows.add(cells);
      }
    }
    int columns = rows.stream().mapToInt(List::size).max().orElse(0);
    int[] widths = new int[columns];
    for (List<String> row : rows) {
      for (int i = 0; i < row.size(); i++) {
        widths[i] = Math.max(widths[i], row.get(i).length());
      }
    }
    for (int r = 0; r < rows.size(); r++) {
      StringBuilder line = new StringBuilder(prefix);
      List<String> row = rows.get(r);
      for (int i = 0; i < columns; i++) {
        String cell = i < row.size() ? row.get(i) : "";
        line.append(cell).append(" ".repeat(widths[i] - cell.length()));
        if (i < columns - 1) {
          line.append("  ");
        }
      }
      out.append(line.toString().stripTrailing()).append('\n');
      if (r == table.head().size() - 1) {
        StringBuilder rule = new StringBuilder(prefix);
        for (int i = 0; i < columns; i++) {
          rule.append("-".repeat(Math.max(1, widths[i]))).append(i < columns - 1 ? "  " : "");
        }
        out.append(rule).append('\n');
      }
    }
    out.append('\n');
  }

  /** Writes wrapped text; the first line starts with {@code first}, the rest with {@code rest}. */
  private void paragraph(String text, String first, String rest) {
    for (String hardLine : text.split("\n", -1)) {
      StringBuilder line = new StringBuilder(first);
      int lineStart = first.length();
      for (String word : hardLine.split(" ")) {
        if (word.isEmpty()) {
          continue;
        }
        if (line.length() > lineStart && line.length() + 1 + word.length() > WIDTH) {
          out.append(line).append('\n');
          line = new StringBuilder(rest);
          lineStart = rest.length();
        }
        if (line.length() > lineStart) {
          line.append(' ');
        }
        line.append(word);
      }
      out.append(line).append('\n');
      first = rest;
    }
    out.append('\n');
  }

  private String text(List<Inline> inlines) {
    StringBuilder text = new StringBuilder();
    for (Inline inline : inlines) {
      switch (inline) {
        case Text t -> text.append(t.text());
        case Link link -> {
          String label = text(link.content());
          text.append(label);
          if (!link.href().startsWith("#") && !label.equals(link.href())) {
            text.append(" <").append(link.href().replaceFirst("^mailto:", "")).append('>');
          }
        }
        case InlineImage image -> {
          if (!image.image().decorative()) {
            text.append('[').append(image.image().alt()).append(']');
          }
        }
        case Footnote footnote -> {
          footnotes.add(footnote.content());
          text.append('[').append(footnotes.size()).append(']');
        }
        case LineBreak lineBreak -> text.append('\n');
      }
    }
    return text.toString();
  }
}
