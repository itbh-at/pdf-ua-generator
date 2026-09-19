/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import at.itbh.pdfuagen.core.model.DocumentModel.Align;
import at.itbh.pdfuagen.core.model.DocumentModel.Literal;
import at.itbh.pdfuagen.core.model.DocumentModel.PageBox;
import at.itbh.pdfuagen.core.model.DocumentModel.PageCount;
import at.itbh.pdfuagen.core.model.DocumentModel.PageNumber;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageBoxesTest {

  @Test
  void readsHeaderAndFooterBoxes() {
    PageBoxes.Result result =
        PageBoxes.parse(
            """
            @page { size: A4; margin: 2cm;
              @top-left { font-size: 9pt; content: "Title \\"x\\""; }
              @bottom-right { content: counter(page) " / " counter(pages); }
            }
            @page :first { @top-left { content: "ignored"; } }
            p { color: red; }
            """);

    assertEquals(
        List.of(new PageBox(Align.LEFT, List.of(new Literal("Title \"x\"")))), result.header());
    assertEquals(
        List.of(
            new PageBox(
                Align.RIGHT, List.of(new PageNumber(), new Literal(" / "), new PageCount()))),
        result.footer());
    assertEquals(List.of(), result.problems());
  }

  @Test
  void reportsUnsupportedContent() {
    PageBoxes.Result result =
        PageBoxes.parse("@page { @bottom-center { content: string(chapter); } }");

    assertEquals(1, result.problems().size());
  }
}
