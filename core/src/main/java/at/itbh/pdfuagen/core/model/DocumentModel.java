/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.model;

import java.util.List;
import java.util.Set;

/**
 * Format-neutral document: the fixed set of building blocks that DOCX, ODT, email HTML and plain
 * text are written from. Built from rendered XHTML by {@link ModelBuilder}.
 *
 * @param title document title ({@code <title>})
 * @param lang document language ({@code <html lang>}), e.g. {@code de-AT}
 * @param description document description ({@code <meta name="description">}), may be empty
 * @param blocks the content
 * @param header running page header, from {@code @page} margin boxes at the top
 * @param footer running page footer, from {@code @page} margin boxes at the bottom
 */
public record DocumentModel(
    String title,
    String lang,
    String description,
    List<Block> blocks,
    List<PageBox> header,
    List<PageBox> footer) {

  public DocumentModel(String title, String lang, String description, List<Block> blocks) {
    this(title, lang, description, blocks, List.of(), List.of());
  }

  /** The same content with running page headers and footers. */
  public DocumentModel withPageBoxes(List<PageBox> header, List<PageBox> footer) {
    return new DocumentModel(title, lang, description, blocks, header, footer);
  }

  /** Horizontal position of a page box in the header or footer. */
  public enum Align {
    LEFT,
    CENTER,
    RIGHT
  }

  /**
   * Running header or footer content from a CSS {@code @page} margin box, e.g. {@code @bottom-right
   * { content: counter(page) " / " counter(pages); }}.
   */
  public record PageBox(Align align, List<PagePart> parts) {}

  /** Part of a page box: literal text, the current page number or the page count. */
  public sealed interface PagePart permits Literal, PageNumber, PageCount {}

  public record Literal(String text) implements PagePart {}

  public record PageNumber() implements PagePart {}

  public record PageCount() implements PagePart {}

  /** Block-level building block. */
  public sealed interface Block
      permits Heading, Paragraph, ListBlock, Table, ImageBlock, Columns, Box, PageBreak {}

  /** Inline building block. */
  public sealed interface Inline permits Text, Link, InlineImage, Footnote, LineBreak {}

  /** Inline emphasis. */
  public enum Mark {
    STRONG,
    EMPHASIS
  }

  /**
   * @param id anchor for internal links, or {@code null}
   * @param style catalog style (CSS class), or {@code null}
   */
  public record Heading(int level, List<Inline> content, String id, String style)
      implements Block {}

  public record Paragraph(List<Inline> content, String style) implements Block {}

  /** Each item is a list of blocks. */
  public record ListBlock(boolean ordered, List<List<Block>> items) implements Block {}

  public record Table(List<Inline> caption, List<Row> head, List<Row> body, List<Row> foot)
      implements Block {}

  public record Row(List<Cell> cells) {}

  /**
   * A table cell; {@code header} for {@code <th>}. {@code colspan}/{@code rowspan} are at least 1.
   */
  public record Cell(boolean header, int colspan, int rowspan, List<Block> content) {}

  public record ImageBlock(Image image) implements Block {}

  /** Side-by-side columns ({@code data-block="columns"}), read in order. */
  public record Columns(List<List<Block>> columns) implements Block {}

  /** Framed box ({@code data-block="box"}) with a catalog style. */
  public record Box(String style, List<Block> content) implements Block {}

  public record PageBreak() implements Block {}

  /**
   * A run of text with uniform formatting.
   *
   * @param lang language of this run if it differs from the document, or {@code null}
   * @param style catalog character style, or {@code null}
   */
  public record Text(String text, Set<Mark> marks, String lang, String style) implements Inline {}

  /** Hyperlink; {@code href} starting with {@code #} points to a heading id. */
  public record Link(String href, List<Inline> content) implements Inline {}

  public record InlineImage(Image image) implements Inline {}

  public record Footnote(List<Inline> content) implements Inline {}

  public record LineBreak() implements Inline {}

  /**
   * An image with its bytes as loaded by the renderer.
   *
   * @param source the resolved URI ({@code template:/…}, {@code attachment:…}, {@code https:…})
   * @param mediaType {@code image/png}, {@code image/jpeg} or {@code image/svg+xml}
   * @param alt alternative text; empty for decorative images
   * @param decorative {@code alt=""} or {@code role="presentation"}
   */
  public record Image(
      String source, byte[] bytes, String mediaType, String alt, boolean decorative) {}
}
