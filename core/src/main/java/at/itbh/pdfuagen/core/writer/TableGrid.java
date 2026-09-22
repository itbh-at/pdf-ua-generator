/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.writer;

import at.itbh.pdfuagen.core.model.DocumentModel.Cell;
import at.itbh.pdfuagen.core.model.DocumentModel.Row;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Lays a section's rows onto a column grid so writers that cannot express a spanning cell by an
 * attribute alone (DOCX, ODT, plain text) know which grid positions a {@code rowspan} covers in the
 * following rows. HTML writers do not need this; they emit the {@code rowspan} attribute and let
 * the renderer build the grid.
 */
final class TableGrid {

  private TableGrid() {}

  /** A position in a physical row: either an authored cell or a position covered from above. */
  sealed interface Slot permits Placed, Covered {}

  /** An authored cell, at its first row; {@link Cell#rowspan()} covers the rows below. */
  record Placed(Cell cell) implements Slot {}

  /**
   * A position covered by a {@code rowspan} started above, spanning {@code columns} grid columns.
   */
  record Covered(int columns) implements Slot {}

  /** The slots of every physical row, in grid-column order, rowspans resolved to covered slots. */
  static List<List<Slot>> layout(List<Row> rows) {
    List<List<Slot>> result = new ArrayList<>();
    // Vertical spans still to cover: {startColumn, colspan, remainingRows}.
    List<int[]> active = new ArrayList<>();
    for (Row row : rows) {
      List<Slot> slots = new ArrayList<>();
      List<int[]> started = new ArrayList<>();
      List<Cell> cells = row.cells();
      int column = 0;
      int index = 0;
      while (index < cells.size() || startsAt(active, column)) {
        int[] span = span(active, column);
        if (span != null) {
          slots.add(new Covered(span[1]));
          column += span[1];
        } else if (index < cells.size()) {
          Cell cell = cells.get(index++);
          slots.add(new Placed(cell));
          if (cell.rowspan() > 1) {
            started.add(new int[] {column, cell.colspan(), cell.rowspan() - 1});
          }
          column += cell.colspan();
        } else {
          break;
        }
      }
      result.add(slots);
      // The active spans covered this row; drop those now exhausted, then add the new ones.
      for (Iterator<int[]> it = active.iterator(); it.hasNext(); ) {
        if (--it.next()[2] <= 0) {
          it.remove();
        }
      }
      active.addAll(started);
    }
    return result;
  }

  private static int[] span(List<int[]> active, int column) {
    for (int[] span : active) {
      if (span[0] == column) {
        return span;
      }
    }
    return null;
  }

  private static boolean startsAt(List<int[]> active, int column) {
    for (int[] span : active) {
      if (span[0] >= column) {
        return true;
      }
    }
    return false;
  }
}
