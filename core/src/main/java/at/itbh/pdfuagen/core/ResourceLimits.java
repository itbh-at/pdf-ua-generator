/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

/**
 * Limits applied to every resource a template loads.
 *
 * @param maxBytes maximum size of one resource
 * @param maxImageSide maximum width or height of a raster image in pixels
 * @param maxImagePixels maximum width × height of a raster image
 */
public record ResourceLimits(long maxBytes, int maxImageSide, long maxImagePixels) {

  public static final ResourceLimits DEFAULT =
      new ResourceLimits(20L * 1024 * 1024, 20_000, 50_000_000L);
}
