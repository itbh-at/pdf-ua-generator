/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Recognizes image formats by their leading bytes and checks raster images against the limits
 * before anything decodes them. Only PNG, JPEG and SVG are allowed; other image formats are
 * rejected, non-image resources (fonts, stylesheets) pass through.
 */
final class ImageGuard {

  enum Kind {
    PNG("image/png"),
    JPEG("image/jpeg"),
    SVG("image/svg+xml"),
    FORBIDDEN_IMAGE(null),
    OTHER(null);

    final String mediaType;

    Kind(String mediaType) {
      this.mediaType = mediaType;
    }
  }

  private ImageGuard() {}

  static Kind detect(byte[] b) {
    if (startsWith(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
      return Kind.PNG;
    }
    if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
      return Kind.JPEG;
    }
    if (startsWith(b, 'G', 'I', 'F', '8')
        || startsWith(b, 'B', 'M')
        || startsWith(b, 'I', 'I', 0x2A, 0x00)
        || startsWith(b, 'M', 'M', 0x00, 0x2A)
        || isWebp(b)
        || isIsoImage(b)) {
      return Kind.FORBIDDEN_IMAGE;
    }
    if (looksLikeSvg(b)) {
      return Kind.SVG;
    }
    return Kind.OTHER;
  }

  /**
   * @return a problem description if the raster image exceeds the limits or cannot be read
   */
  static Optional<String> checkDimensions(byte[] bytes, ResourceLimits limits) {
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) {
        return Optional.of("unreadable image");
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in, true, true);
        long width = reader.getWidth(0);
        long height = reader.getHeight(0);
        if (width > limits.maxImageSide()
            || height > limits.maxImageSide()
            || width * height > limits.maxImagePixels()) {
          return Optional.of("image of " + width + " × " + height + " pixels exceeds the limits");
        }
        return Optional.empty();
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      return Optional.of("unreadable image: " + e.getMessage());
    }
  }

  private static boolean looksLikeSvg(byte[] b) {
    int n = Math.min(b.length, 1024);
    String head = new String(b, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
    return head.contains("<svg");
  }

  private static boolean isWebp(byte[] b) {
    return startsWith(b, 'R', 'I', 'F', 'F')
        && b.length >= 12
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P';
  }

  /** HEIC and AVIF: ISO base media file with an "ftyp" box. */
  private static boolean isIsoImage(byte[] b) {
    if (b.length < 12 || b[4] != 'f' || b[5] != 't' || b[6] != 'y' || b[7] != 'p') {
      return false;
    }
    String brand = new String(b, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    return brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("avi");
  }

  private static boolean startsWith(byte[] b, int... prefix) {
    if (b.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if ((b[i] & 0xFF) != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
