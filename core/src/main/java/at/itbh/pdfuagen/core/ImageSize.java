/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Intrinsic size of an image in CSS pixels, read without decoding the pixels. */
public final class ImageSize {

  private static final Pattern VIEW_BOX =
      Pattern.compile(
          "viewBox\\s*=\\s*[\"']\\s*[-\\d.]+[\\s,]+[-\\d.]+[\\s,]+([\\d.]+)[\\s,]+([\\d.]+)");
  private static final Pattern SVG_WIDTH =
      Pattern.compile("<svg[^>]*\\swidth\\s*=\\s*[\"']([\\d.]+)(?:px)?[\"']");
  private static final Pattern SVG_HEIGHT =
      Pattern.compile("<svg[^>]*\\sheight\\s*=\\s*[\"']([\\d.]+)(?:px)?[\"']");

  private ImageSize() {}

  /** Width and height; empty if the image cannot be measured. */
  public static Optional<int[]> of(byte[] bytes, String mediaType) {
    if ("image/svg+xml".equals(mediaType)) {
      return svg(bytes);
    }
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) {
        return Optional.empty();
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in, true, true);
        return Optional.of(new int[] {reader.getWidth(0), reader.getHeight(0)});
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static Optional<int[]> svg(byte[] bytes) {
    String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8);
    Matcher width = SVG_WIDTH.matcher(head);
    Matcher height = SVG_HEIGHT.matcher(head);
    if (width.find() && height.find()) {
      return Optional.of(
          new int[] {
            Math.round(Float.parseFloat(width.group(1))),
            Math.round(Float.parseFloat(height.group(1)))
          });
    }
    Matcher box = VIEW_BOX.matcher(head);
    if (box.find()) {
      return Optional.of(
          new int[] {
            Math.round(Float.parseFloat(box.group(1))), Math.round(Float.parseFloat(box.group(2)))
          });
    }
    return Optional.empty();
  }
}
