/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core.writer;

import at.itbh.pdfuagen.core.model.DocumentModel.Image;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.batik.transcoder.SVGAbstractTranscoder;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

/** Raster versions and sizes of images for the office formats. */
final class Images {

  /** A raster image ready to embed. */
  record Raster(byte[] bytes, String mediaType, String extension, int width, int height) {}

  private static final float SVG_SCALE = 2f;

  private Images() {}

  /** PNG and JPEG unchanged; SVG rasterized to PNG without scripts or external resources. */
  static Raster raster(Image image) throws IOException {
    byte[] bytes = image.bytes();
    String type = image.mediaType();
    if ("image/svg+xml".equals(type)) {
      bytes = svgToPng(bytes);
      type = "image/png";
    }
    int[] size =
        at.itbh.pdfuagen.core.ImageSize.of(bytes, type)
            .orElseThrow(() -> new IOException("unreadable image"));
    boolean svg = "image/svg+xml".equals(image.mediaType());
    return new Raster(
        bytes,
        type,
        type.equals("image/jpeg") ? "jpeg" : "png",
        svg ? Math.round(size[0] / SVG_SCALE) : size[0],
        svg ? Math.round(size[1] / SVG_SCALE) : size[1]);
  }

  private static byte[] svgToPng(byte[] svg) throws IOException {
    PNGTranscoder transcoder = new PNGTranscoder();
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false);
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, false);
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOWED_SCRIPT_TYPES, "");
    transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_CONSTRAIN_SCRIPT_ORIGIN, true);
    // Render at twice the intrinsic size for print quality; the size in the document stays.
    at.itbh.pdfuagen.core.ImageSize.of(svg, "image/svg+xml")
        .ifPresent(
            size ->
                transcoder.addTranscodingHint(
                    SVGAbstractTranscoder.KEY_WIDTH, (float) size[0] * SVG_SCALE));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      transcoder.transcode(
          new TranscoderInput(new ByteArrayInputStream(svg)), new TranscoderOutput(out));
    } catch (TranscoderException e) {
      throw new IOException("SVG cannot be rendered: " + e.getMessage(), e);
    }
    return out.toByteArray();
  }
}
