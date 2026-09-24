/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;

/**
 * The font files of a layout. Every font is embedded into PDF, DOCX and ODT, so a font must allow
 * it: its OS/2 {@code fsType} must not mark it "restricted license" (no embedding at all) or
 * "bitmap only" (no outlines to embed).
 */
public final class FontFiles {

  private static final int RESTRICTED = 0x0002;
  private static final int EMBEDDING_MASK = 0x000F;
  private static final int BITMAP_ONLY = 0x0200;

  private FontFiles() {}

  /** Whether a path names a font file this check reads (TrueType or OpenType). */
  public static boolean isFont(String path) {
    String name = path.toLowerCase(Locale.ROOT);
    return name.endsWith(".ttf") || name.endsWith(".otf");
  }

  /**
   * Why a font file cannot be embedded, if it cannot.
   *
   * @param path the file's path, for the problem location
   * @return a problem if the file is not a readable font or forbids embedding
   */
  public static Optional<Problem> embeddingProblem(String path, byte[] bytes) {
    if (!isFont(path)) {
      return Optional.empty();
    }
    try (TrueTypeFont font =
        path.toLowerCase(Locale.ROOT).endsWith(".otf")
            ? new OTFParser().parse(new RandomAccessReadBuffer(bytes))
            : new TTFParser().parse(new RandomAccessReadBuffer(bytes))) {
      OS2WindowsMetricsTable os2 = font.getOS2Windows();
      if (os2 == null) {
        return Optional.empty();
      }
      int fsType = os2.getFsType() & 0xFFFF;
      if ((fsType & EMBEDDING_MASK) == RESTRICTED) {
        return Optional.of(
            problem(
                "the font forbids embedding (OS/2 fsType: restricted license); it cannot be part"
                    + " of a PDF, DOCX or ODT. Use a font whose licence allows embedding",
                path));
      }
      if ((fsType & BITMAP_ONLY) != 0) {
        return Optional.of(
            problem(
                "the font allows embedding only as bitmaps (OS/2 fsType); PDF/UA needs its"
                    + " outlines. Use another font",
                path));
      }
      return Optional.empty();
    } catch (IOException | RuntimeException e) {
      return Optional.of(
          problem("not a readable TrueType or OpenType font: " + e.getMessage(), path));
    }
  }

  private static Problem problem(String detail, String path) {
    return new Problem(Problem.TEMPLATE_ERROR, detail, path);
  }
}
