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
  XHTML("application/xhtml+xml", "xhtml"),

  /** HTML for email bodies: inlined styles from {@code email.css}, absolute image URLs. */
  EMAIL_HTML("text/html", "html"),

  /** Plain text, UTF-8, wrapped at 72 characters. */
  TEXT("text/plain", "txt"),

  /** WordprocessingML. */
  DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),

  /** OpenDocument text, ODF 1.3. */
  ODT("application/vnd.oasis.opendocument.text", "odt");

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
