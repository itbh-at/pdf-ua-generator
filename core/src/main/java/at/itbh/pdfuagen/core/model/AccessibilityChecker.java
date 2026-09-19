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
import at.itbh.pdfuagen.core.model.DocumentModel.Link;
import at.itbh.pdfuagen.core.model.DocumentModel.ListBlock;
import at.itbh.pdfuagen.core.model.DocumentModel.Paragraph;
import at.itbh.pdfuagen.core.model.DocumentModel.Row;
import at.itbh.pdfuagen.core.model.DocumentModel.Table;
import at.itbh.pdfuagen.core.model.DocumentModel.Text;
import java.util.ArrayList;
import java.util.List;

/**
 * Accessibility rules checked once on the model, for every format written from it: title and
 * language set, no skipped heading levels, no empty headings, alt text on every informative image,
 * a header row or header cells in every table, text in every link.
 */
public final class AccessibilityChecker {

  private final List<Problem> problems = new ArrayList<>();
  private int lastHeading;

  private AccessibilityChecker() {}

  public static List<Problem> check(DocumentModel model) {
    AccessibilityChecker checker = new AccessibilityChecker();
    if (model.title().isBlank()) {
      checker.problem("the document has no title", "/html/head/title");
    }
    if (model.lang().isBlank()) {
      checker.problem("the document language is not set", "/html/@lang");
    }
    checker.blocks(model.blocks());
    return List.copyOf(checker.problems);
  }

  private void blocks(List<Block> blocks) {
    for (Block block : blocks) {
      switch (block) {
        case Heading heading -> {
          if (heading.level() > lastHeading + 1) {
            problem(
                "heading level " + heading.level() + " follows level " + lastHeading,
                "h" + heading.level() + " \"" + text(heading.content()) + "\"");
          }
          if (text(heading.content()).isBlank()) {
            problem("empty heading", "h" + heading.level());
          }
          lastHeading = heading.level();
          inlines(heading.content());
        }
        case Paragraph paragraph -> inlines(paragraph.content());
        case ListBlock list -> list.items().forEach(this::blocks);
        case Table table -> table(table);
        case ImageBlock image -> image(image.image());
        case Columns columns -> columns.columns().forEach(this::blocks);
        case Box box -> blocks(box.content());
        default -> {}
      }
    }
  }

  private void table(Table table) {
    boolean hasHeader =
        !table.head().isEmpty()
            || table.body().stream().flatMap(r -> r.cells().stream()).anyMatch(Cell::header);
    if (!hasHeader) {
      problem("table without header cells", "table \"" + text(table.caption()) + "\"");
    }
    inlines(table.caption());
    for (List<Row> rows : List.of(table.head(), table.body(), table.foot())) {
      rows.forEach(r -> r.cells().forEach(c -> blocks(c.content())));
    }
  }

  private void inlines(List<Inline> inlines) {
    for (Inline inline : inlines) {
      switch (inline) {
        case Link link -> {
          if (text(link.content()).isBlank()
              && link.content().stream()
                  .noneMatch(i -> i instanceof InlineImage img && !img.image().decorative())) {
            problem("link without text", link.href());
          }
          inlines(link.content());
        }
        case InlineImage image -> image(image.image());
        case Footnote footnote -> inlines(footnote.content());
        default -> {}
      }
    }
  }

  private void image(Image image) {
    if (!image.decorative() && (image.alt() == null || image.alt().isBlank())) {
      problem("image without alternative text", image.source());
    }
  }

  private void problem(String detail, String location) {
    problems.add(new Problem(Problem.ACCESSIBILITY, detail, location));
  }

  static String text(List<Inline> inlines) {
    StringBuilder out = new StringBuilder();
    for (Inline inline : inlines) {
      switch (inline) {
        case Text text -> out.append(text.text());
        case Link link -> out.append(text(link.content()));
        default -> {}
      }
    }
    return out.toString();
  }
}
