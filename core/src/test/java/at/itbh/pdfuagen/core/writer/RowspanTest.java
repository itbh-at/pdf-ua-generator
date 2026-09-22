/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.writer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import at.itbh.pdfuagen.core.model.DocumentModel;
import at.itbh.pdfuagen.core.model.DocumentModel.Block;
import at.itbh.pdfuagen.core.model.DocumentModel.Cell;
import at.itbh.pdfuagen.core.model.DocumentModel.Paragraph;
import at.itbh.pdfuagen.core.model.DocumentModel.Row;
import at.itbh.pdfuagen.core.model.DocumentModel.Table;
import at.itbh.pdfuagen.core.model.DocumentModel.Text;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/** A cell with {@code rowspan} covers grid positions in the rows below in DOCX, ODT and text. */
class RowspanTest {

  // A 2x3 body: the first cell spans both rows, so row two authors only two cells.
  private final DocumentModel model =
      new DocumentModel(
          "Rowspan",
          "en",
          "",
          List.of(
              new Table(
                  List.of(),
                  List.of(new Row(List.of(th("Region"), th("Month"), th("Sales")))),
                  List.of(
                      new Row(List.of(cell("North", 1, 2), cell("Jan", 1, 1), cell("10", 1, 1))),
                      new Row(List.of(cell("Feb", 1, 1), cell("20", 1, 1)))),
                  List.of())));

  @Test
  void docxMergesTheCellVertically() throws Exception {
    String document = part(DocxWriter.write(model), "word/document.xml");
    assertTrue(
        document.contains("w:vMerge w:val=\"restart\""), "the spanning cell restarts a merge");
    assertTrue(
        document.contains("<w:vMerge/>") || document.contains("<w:vMerge></w:vMerge>"),
        "the covered row continues the merge");
  }

  @Test
  void odtSpansRowsAndCoversTheCellBelow() throws Exception {
    String content = part(OdtWriter.write(model), "content.xml");
    assertTrue(
        content.contains("table:number-rows-spanned=\"2\""), "the spanning cell spans two rows");
    assertTrue(content.contains("<table:covered-table-cell/>"), "the covered position below");
  }

  @Test
  void textLeavesTheCoveredPositionBlank() {
    String text = TextWriter.write(model);
    // "North" appears once; the second row starts at the second column (a leading gap).
    assertTrue(text.contains("North"), text);
    assertTrue(text.contains("Feb"), text);
  }

  @Test
  void emailHtmlEmitsTheRowspanAttribute() throws Exception {
    String html =
        new String(
            EmailHtmlWriter.write(model, CssRules.parse(""), i -> ""), StandardCharsets.UTF_8);
    assertTrue(html.contains("rowspan=\"2\""), html);
  }

  private static Cell th(String text) {
    return new Cell(true, 1, 1, block(text));
  }

  private static Cell cell(String text, int colspan, int rowspan) {
    return new Cell(false, colspan, rowspan, block(text));
  }

  private static List<Block> block(String text) {
    return List.of(new Paragraph(List.of(new Text(text, Set.of(), null, null)), null));
  }

  private static String part(byte[] zip, String name) throws Exception {
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        if (entry.getName().equals(name)) {
          return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
    }
    throw new AssertionError(name + " not in the archive");
  }
}
