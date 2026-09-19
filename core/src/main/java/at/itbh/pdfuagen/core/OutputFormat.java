/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

/** Output formats of the renderer. */
public enum OutputFormat {

  /** Tagged PDF conforming to PDF/UA-1 (PDF 1.7). */
  PDF("pdf", "application/pdf", "pdf"),

  /** Self-contained XHTML: images, fonts and stylesheets embedded as data URIs. */
  XHTML("xhtml", "application/xhtml+xml", "xhtml"),

  /** HTML for email bodies: inlined styles from {@code email.css}, absolute image URLs. */
  EMAIL_HTML("email-html", "text/html", "html"),

  /** Plain text, UTF-8, wrapped at 72 characters. */
  TEXT("text", "text/plain", "txt"),

  /** WordprocessingML. */
  DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),

  /** OpenDocument text, ODF 1.3. */
  ODT("odt", "application/vnd.oasis.opendocument.text", "odt");

  private final String id;
  private final String mediaType;
  private final String extension;

  OutputFormat(String id, String mediaType, String extension) {
    this.id = id;
    this.mediaType = mediaType;
    this.extension = extension;
  }

  /** The name used in descriptors, on the command line and in the API, e.g. {@code email-html}. */
  public String id() {
    return id;
  }

  /** The format with the given {@link #id()}. */
  public static java.util.Optional<OutputFormat> of(String id) {
    return java.util.Arrays.stream(values()).filter(f -> f.id.equals(id)).findFirst();
  }

  public String mediaType() {
    return mediaType;
  }

  public String extension() {
    return extension;
  }
}
