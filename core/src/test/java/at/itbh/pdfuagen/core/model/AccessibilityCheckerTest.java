/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import at.itbh.pdfuagen.core.Problem;
import at.itbh.pdfuagen.core.model.DocumentModel.Cell;
import at.itbh.pdfuagen.core.model.DocumentModel.Heading;
import at.itbh.pdfuagen.core.model.DocumentModel.Image;
import at.itbh.pdfuagen.core.model.DocumentModel.ImageBlock;
import at.itbh.pdfuagen.core.model.DocumentModel.Inline;
import at.itbh.pdfuagen.core.model.DocumentModel.Paragraph;
import at.itbh.pdfuagen.core.model.DocumentModel.Row;
import at.itbh.pdfuagen.core.model.DocumentModel.Table;
import at.itbh.pdfuagen.core.model.DocumentModel.Text;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AccessibilityCheckerTest {

  private static List<Inline> text(String value) {
    return List.of(new Text(value, Set.of(), null, null));
  }

  @Test
  void acceptsAnAccessibleDocument() {
    DocumentModel model =
        new DocumentModel(
            "Title",
            "en",
            "",
            List.of(
                new Heading(1, text("A"), null, null),
                new Heading(2, text("B"), null, null),
                new ImageBlock(
                    new Image("template:/a.png", new byte[0], "image/png", "Alt", false)),
                new ImageBlock(new Image("template:/d.png", new byte[0], "image/png", "", true))));

    assertEquals(List.of(), AccessibilityChecker.check(model));
  }

  @Test
  void reportsEveryViolation() {
    Row row = new Row(List.of(new Cell(false, 1, 1, List.of(new Paragraph(text("x"), null)))));
    DocumentModel model =
        new DocumentModel(
            "",
            "",
            "",
            List.of(
                new Heading(1, text("A"), null, null),
                new Heading(3, text(""), null, null),
                new ImageBlock(new Image("template:/a.png", new byte[0], "image/png", null, false)),
                new Table(List.of(), List.of(), List.of(row), List.of())));

    List<Problem> problems = AccessibilityChecker.check(model);
    assertEquals(6, problems.size(), problems::toString);
    assertTrue(problems.stream().allMatch(p -> p.type().equals(Problem.ACCESSIBILITY)));
  }
}
