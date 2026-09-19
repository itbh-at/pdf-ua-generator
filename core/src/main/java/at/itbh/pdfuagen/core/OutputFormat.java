/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

/** Output formats of the renderer. */
public enum OutputFormat {

  /** Tagged PDF conforming to PDF/UA-1 (PDF 1.7). */
  PDF("application/pdf", "pdf"),

  /** Self-contained XHTML: images, fonts and stylesheets embedded as data URIs. */
  XHTML("application/xhtml+xml", "xhtml");

  private final String mediaType;
  private final String extension;

  OutputFormat(String mediaType, String extension) {
    this.mediaType = mediaType;
    this.extension = extension;
  }

  public String mediaType() {
    return mediaType;
  }

  public String extension() {
    return extension;
  }
}
